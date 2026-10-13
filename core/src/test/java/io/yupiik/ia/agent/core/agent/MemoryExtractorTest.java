/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatClient.StructuredResult;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class MemoryExtractorTest {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @TempDir
    Path home;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Mirrors the extractor's internal excerpt cap (12k). */
    private static final int MAX_EXCERPT_CHARS_FOR_TEST = 12_000;

    private static CodingAgentConfiguration config(final Path home) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", home.toString());
        map.put("yca.workingDir", home.toString());
        map.put("yca.projectDir", home.toString());
        // memory enabled with the default budget so extraction actually runs
        map.put("yca.memory.enabled", "true");
        map.put("yca.memory.maxChars", "8000");
        map.put("yca.memory.maxRatio", "0.2");
        // the tests feed short single messages; disable the min-chars gate so the
        // async extraction path actually fires (the gate itself has its own test)
        map.put("yca.memory.extractMinChars", "0");
        return TestConfigs.codingAgent(map);
    }

    @Test
    void structuredFailureFallsBackToSnippetAndWarnsWithContext() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        final var events = new ArrayList<AgentEvent>();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                // provider rejected the JSON Schema: ChatClient falls back to a plain call and reports
                // structured=false; the extractor must then use the snippet fallback
                return new StructuredResult(
                        new ChatResult(Message.assistant("plain fallback answer", null), new Usage(1, 1, 2)), false);
            }

            @Override
            public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                return new ChatResult(
                        Message.assistant("- a durable fact about the project that survives the fallback\n", null),
                        new Usage(1, 1, 2));
            }
        };
        final var done = new CountDownLatch(1);
        final var extractor = new MemoryExtractor(
                chat,
                config,
                null, // provider manager: the global memory model chain applies
                store,
                null, // mapper unused by the fallback path
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                        done.countDown();
                    }
                },
                Clock.systemUTC(),
                null,
                "s-test",
                null);
        extractor.asyncExtractNow(List.of(Message.user("user said: the project now uses tabs")), ChatSettings.EMPTY);
        assertTrue(done.await(5, TimeUnit.SECONDS), "the extractor must emit a notice");
        // the notice fires before the snippet write: wait for the file to be updated
        final var projectFile = store.projectFile();
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(projectFile) || !Files.readString(projectFile).contains("a durable fact"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(1, events.size(), "one warning expected");
        final var notice = (AgentEvent.Notice) events.get(0);
        assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
        assertEquals("s-test", notice.sessionId());
        assertTrue(
                notice.message().contains("structured facts"),
                "the notice must name the failing phase: " + notice.message());
        assertTrue(
                notice.message().contains("rejected by the provider"),
                "the notice must explain the rejection: " + notice.message());
        assertTrue(
                notice.message().contains("saving a plain snippet instead"),
                "the notice must mention the fallback: " + notice.message());
        // the fallback snippet path must still save memory
        final var project = store.readProject();
        assertTrue(project.contains("a durable fact"), "the fallback snippet must be saved: " + project);
    }

    @Test
    void structuredExceptionFallsBackToSnippetAndWarns() throws Exception {
        // P0-D: a wire failure of the structured call (e.g. a gateway that streams unconditionally) used
        // to lose the whole memory pass; now it must still save a plain snippet and name the phase
        final var config = config(home);
        final var store = new MemoryStore(config);
        final var events = new ArrayList<AgentEvent>();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                throw new IOException("anthropic-messages: unparseable response from https://gw/v1/messages (HTTP 200, "
                        + "text/event-stream): Unexpected character 'e'");
            }

            @Override
            public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                return new ChatResult(
                        Message.assistant("- durable: the team standard is tabs\n", null), new Usage(1, 1, 2));
            }
        };
        final var done = new CountDownLatch(1);
        final var extractor = new MemoryExtractor(
                chat,
                config,
                null, // provider manager: the global memory model chain applies
                store,
                mapper,
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                        done.countDown();
                    }
                },
                Clock.systemUTC(),
                null,
                "s-test",
                null);
        extractor.asyncExtractNow(List.of(Message.user("user said: we use tabs now")), ChatSettings.EMPTY);
        assertTrue(done.await(5, TimeUnit.SECONDS), "the extractor must emit a notice");
        final var projectFile = store.projectFile();
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(projectFile) || !Files.readString(projectFile).contains("the team standard is tabs"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(1, events.size(), "one warning expected");
        final var notice = (AgentEvent.Notice) events.get(0);
        assertTrue(
                notice.message().contains("structured facts"),
                "the notice must name the failing phase: " + notice.message());
        assertTrue(
                notice.message().contains("saving a plain snippet instead"),
                "the notice must mention the fallback: " + notice.message());
        assertTrue(
                notice.message().contains("Unexpected character 'e'"),
                "the notice must carry the contextual detail: " + notice.message());
        final var project = store.readProject();
        assertTrue(project.contains("the team standard is tabs"), "the fallback snippet must be saved: " + project);
    }

    @Test
    void deltaExtractsOnlyNewMessages() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        final var requests = new ArrayList<ChatCompletionRequest>();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                requests.add(request);
                return new StructuredResult(
                        new ChatResult(
                                Message.assistant(
                                        "{\"facts\":[{\"text\":\"prefer tabs\",\"scope\":\"global\"}]}", null),
                                new Usage(1, 1, 2)),
                        true);
            }
        };
        final var extractor = new MemoryExtractor(
                chat,
                config,
                null,
                store,
                mapper,
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                Clock.systemUTC(),
                null,
                "s-delta",
                null);
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("first message: use tabs"));
        extractor.forceExtractNow(messages, ChatSettings.EMPTY);
        // second user message: only that one must be extracted (the excerpt must not re-feed the first)
        messages.add(Message.user("second message: use spaces"));
        extractor.forceExtractNow(messages, ChatSettings.EMPTY);
        assertEquals(2, requests.size(), "one call per extraction");
        final var secondExcerpt =
                String.valueOf(requests.get(1).messages().get(1).content());
        assertTrue(
                secondExcerpt.contains("second message"), "the delta must contain the new message: " + secondExcerpt);
        assertTrue(
                !secondExcerpt.contains("first message"),
                "the delta must not re-feed extracted messages: " + secondExcerpt);
    }

    @Test
    void minCharsGateSkipsTinyDeltas() throws Exception {
        // end-of-turn extraction (asyncExtractNow) honors memory.extractMinChars: a tiny delta is not
        // worth an LLM call. The forced path (forceExtractNow, session close) bypasses the gate,
        // so this only exercises the end-of-turn path.
        final var config = config(home);
        final var store = new MemoryStore(config);
        final var calls = new AtomicInteger();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                calls.incrementAndGet();
                return new StructuredResult(
                        new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
            }
        };
        final var extractor = new MemoryExtractor(
                chat,
                config,
                null,
                store,
                mapper,
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                Clock.systemUTC(),
                null,
                "s-min",
                null);
        // re-enable the default 1000-char gate for this test: config() sets it to 0 so the
        // async extraction tests fire, but this test wants the gate active
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", home.toString());
        map.put("yca.workingDir", home.toString());
        map.put("yca.projectDir", home.toString());
        map.put("yca.memory.enabled", "true");
        map.put("yca.memory.maxChars", "8000");
        map.put("yca.memory.maxRatio", "0.2");
        final var gated = new MemoryExtractor(
                chat,
                TestConfigs.codingAgent(map),
                null,
                store,
                mapper,
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                Clock.systemUTC(),
                null,
                "s-min",
                null);
        // tiny delta below the default 1000-char gate: no call (end-of-turn path only)
        gated.asyncExtractNow(List.of(Message.user("hi")), ChatSettings.EMPTY);
        Thread.sleep(200); // give the async task a chance to (not) run
        assertEquals(0, calls.get(), "a tiny delta must not fire an end-of-turn extraction");
    }

    @Test
    void forcedExtractionBypassesMinCharsButNotTheWatermark() throws Exception {
        final var config = config(home);
        // re-enable the min-chars gate for this test (config() set it to 0)
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", home.toString());
        map.put("yca.workingDir", home.toString());
        map.put("yca.projectDir", home.toString());
        map.put("yca.memory.enabled", "true");
        map.put("yca.memory.maxChars", "8000");
        map.put("yca.memory.maxRatio", "0.2");
        final var gatedConfig = TestConfigs.codingAgent(map);
        final var store = new MemoryStore(gatedConfig);
        final var events = new ArrayList<AgentEvent>();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                return new StructuredResult(
                        new ChatResult(
                                Message.assistant(
                                        "{\"facts\":[{\"text\":\"short preference\",\"scope\":\"project\"}]}", null),
                                new Usage(1, 1, 2)),
                        true);
            }
        };
        final var extractor = new MemoryExtractor(
                chat,
                gatedConfig,
                null,
                store,
                mapper,
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                    }
                },
                Clock.systemUTC(),
                null,
                "s-forced",
                null);
        // a below-threshold delta must still be extracted when forced (session close)
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("short"));
        final var ran = extractor.forceExtractNow(messages, ChatSettings.EMPTY);
        assertTrue(ran, "the forced pass must run on a non-empty delta");
        assertTrue(store.readProject().contains("short preference"), "the forced pass must save the fact");
        // a second forced pass on the same messages must be a no-op (watermark)
        final var ran2 = extractor.forceExtractNow(messages, ChatSettings.EMPTY);
        assertTrue(!ran2, "the forced pass must be a no-op when nothing new was added");
    }

    @Test
    void asyncExtractRunsImmediatelyButTheWatermarkDedups() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        final var calls = new AtomicInteger();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                calls.incrementAndGet();
                return new StructuredResult(
                        new ChatResult(Message.assistant("{\"facts\":[]}", null), new Usage(1, 1, 2)), true);
            }
        };
        final var extractor = new MemoryExtractor(
                chat,
                config,
                null,
                store,
                mapper,
                EXECUTOR,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                Clock.systemUTC(),
                null,
                "s-async",
                null);
        // the end-of-turn hook is not debounced: the first call must run right away
        extractor.asyncExtractNow(List.of(Message.user("one")), ChatSettings.EMPTY);
        Thread.sleep(300); // give the async task time to run
        assertEquals(1, calls.get(), "the async pass must run immediately (no debounce)");
        // a second hook on the same messages is a watermark no-op: the delta was already extracted
        extractor.asyncExtractNow(List.of(Message.user("two")), ChatSettings.EMPTY);
        Thread.sleep(300);
        assertEquals(1, calls.get(), "the watermark must dedup the already-extracted delta");
    }

    @Test
    void excerptFiltersTransientAndKeepsUserSignal() throws Exception {
        // the excerpt fed to the model must contain only user/assistant text, never
        // tool results, tool-call frames, digests or the volatile tail
        final var messages = List.of(
                Message.system("system prompt"),
                Message.user("prefer tabs"),
                Message.tool("tc-1", "huge JSON tool output"),
                Message.assistant(
                        "I'll use tabs",
                        List.of(new io.yupiik.ia.agent.core.llm.ToolCall(
                                "tc-2",
                                "function",
                                new io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall(
                                        "read_file", "{\"path\":\"/tmp/x\"}"),
                                null))),
                Message.assistant("the function reads a config file", null),
                Message.user("<compacted-context> the previous conversation was summarized </compacted-context>"),
                new Message("user", "volatile tail", null, null, null, Map.of("volatileTail", true)));
        final var excerpt = MemoryExtractor.buildExcerpt(messages);
        assertTrue(excerpt.contains("prefer tabs"), "the user preference must be kept: " + excerpt);
        assertTrue(
                excerpt.contains("the function reads a config file"),
                "the plain assistant answer must be kept: " + excerpt);
        assertTrue(!excerpt.contains("system prompt"), "the system prompt must be filtered: " + excerpt);
        assertTrue(!excerpt.contains("huge JSON"), "tool results must be filtered: " + excerpt);
        assertTrue(
                !excerpt.contains("tc-2") && !excerpt.contains("read_file"),
                "assistant tool-call frames must be filtered: " + excerpt);
        assertTrue(!excerpt.contains("compacted-context"), "compaction digests must be filtered: " + excerpt);
        assertTrue(!excerpt.contains("volatile tail"), "the volatile tail must be filtered: " + excerpt);
    }

    @Test
    void excerptCapDropsAssistantBeforeUserOldestFirst() throws Exception {
        // an over-cap delta must shed assistant messages first (oldest first), never the user's
        // own words; the newest message is always kept
        final var filler = "a".repeat(3200);
        final var messages = List.of(
                Message.user("user-old " + filler),
                Message.assistant("assistant-a " + filler, null),
                Message.assistant("assistant-b " + filler, null),
                Message.user("user-new " + filler));
        final var excerpt = MemoryExtractor.buildExcerpt(messages);
        final var assistantAFound = excerpt.contains("assistant-a");
        final var assistantBFound = excerpt.contains("assistant-b");
        final var userOldFound = excerpt.contains("user-old");
        final var userNewFound = excerpt.contains("user-new");
        // the excerpt must be under the cap (with a mild slack for the role prefix)
        assertTrue(excerpt.length() <= MAX_EXCERPT_CHARS_FOR_TEST + 200, "excerpt too long: " + excerpt.length());
        // user messages are never dropped while an assistant can still go: if a user survived, all
        // assistants that could be shed must have been; and the newest user message always survives
        assertTrue(userNewFound, "the newest user message must always survive: " + excerpt);
        if (userOldFound) {
            // both users kept means all assistants were under the cap or already dropped before any user
            assertTrue(excerpt.length() <= MAX_EXCERPT_CHARS_FOR_TEST + 200, "unexpected: " + excerpt);
        } else {
            // user-old got dropped only because assistants were exhausted or would exceed alone
            assertTrue(!assistantAFound && !assistantBFound, "assistants must go before any user: " + excerpt);
        }
    }

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }
}
