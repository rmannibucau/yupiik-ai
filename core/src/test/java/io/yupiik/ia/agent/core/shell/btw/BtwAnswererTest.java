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
package io.yupiik.ia.agent.core.shell.btw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupParser;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupStripper;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.BashTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class BtwAnswererTest {
    @TempDir
    Path storageHome;

    /** A realistic DSML tool-call block as emitted by DeepSeek V4. */
    private static final String DSML_BLOCK = "<\uFF5CDSML\uFF5Ctool_calls>\n"
            + "<\uFF5CDSML\uFF5Cinvoke name=read_file>\n"
            + "<\uFF5CDSML\uFF5Cparameter name=path string=true>src/App.java</\uFF5CDSML\uFF5Cparameter>\n"
            + "</\uFF5CDSML\uFF5Cinvoke>\n"
            + "</\uFF5CDSML\uFF5Ctool_calls>";

    private static final Emitter NOOP = new Emitter() {
        @Override
        public <T> void emit(final T event) {
            // no-op
        }
    };

    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private java.util.concurrent.ExecutorService btwExecutor;
    private InMemorySessionStore sessionStore;
    private UsageTracker usageTracker;
    private BtwAnswerer answerer;
    private ToolRegistry toolRegistry;
    private JsonMapper mapper;
    private final List<ChatClient> clients = new ArrayList<>();
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start(final JsonMapper mapper) throws IOException {
        this.mapper = mapper;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            respond(exchange, 200, response(body, bodies.size()), "application/json");
        });
        serverExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        sessionStore = new InMemorySessionStore();
        usageTracker = new UsageTracker(null, null, null, Clock.systemUTC());
        toolRegistry = registry();
        answerer = answerer(Map.of());
    }

    @AfterEach
    void stop() {
        clients.forEach(ChatClient::close);
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (btwExecutor != null) {
            btwExecutor.shutdownNow();
        }
    }

    private BtwAnswerer answerer(final Map<String, String> extra) {
        final var configuration = TestConfigs.codingAgent(config(extra));
        final var providerManager = new ProviderManager(configuration, mapper);
        final var client = new ChatClient(configuration, mapper, providerManager, Clock.systemUTC());
        clients.add(client);
        btwExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return new BtwAnswerer(
                client,
                sessionStore,
                configuration,
                btwExecutor,
                new ToolCallMarkupStripper(),
                new ToolCallMarkupParser(),
                toolRegistry,
                new ToolExecutionService(configuration, NOOP, List.of()),
                mapper,
                providerManager,
                Clock.systemUTC(),
                NOOP,
                null);
    }

    /**
     * The /btw tool set: a fake read-only file reader, a fake writer (never allowed in /btw),
     * and the real bash tool whose plan-mode rules gate which commands may actually run.
     */
    private ToolRegistry registry() {
        final var tools =
                List.<EmbeddedTool>of(readFileTool(), writeFileTool(), new BashTool(NOOP, new ProcessService()));
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return tools;
            }
        };
    }

    private static EmbeddedTool readFileTool() {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "read a file";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public boolean readonly() {
                return true;
            }

            @Override
            public ToolResult execute(final Map<String, Object> args) {
                return ToolResult.ok("file content: " + args.get("path"));
            }
        };
    }

    private static EmbeddedTool writeFileTool() {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return "write_file";
            }

            @Override
            public String description() {
                return "write a file";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.EDIT;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(final Map<String, Object> args) {
                return ToolResult.error("must never run: write_file is not a /btw tool");
            }
        };
    }

    /**
     * What the stub returns for a given request, driven by magic words in the question. The
     * round-trip scenarios return a tool call for the first request and the final answer for
     * the following ones, mirroring a real model answering with tools.
     */
    private String response(final String body, final int request) {
        if (body.contains("NATIVE READ")) {
            return request == 1
                    ? toolResponse("read_file", "{\"path\":\"src/App.java\"}")
                    : textResponse("read round-trip done");
        }
        if (body.contains("BASH CAT")) {
            return request == 1
                    ? toolResponse("bash", "{\"command\":\"cat " + catPath() + "\"}")
                    : textResponse("cat done");
        }
        if (body.contains("BASH RM")) {
            return request == 1
                    ? toolResponse("bash", "{\"command\":\"rm -rf " + catPath() + "\"}")
                    : textResponse("rm done");
        }
        if (body.contains("WRITE TOOL")) {
            return request == 1
                    ? toolResponse("write_file", "{\"path\":\"" + catPath() + "\",\"content\":\"x\"}")
                    : textResponse("write done");
        }
        if (body.contains("TOOLCALL MARKUP")) {
            // first request carries the markup the parser must turn into a real read_file call
            return request == 1
                    ? textResponse("the parsed answer\n\n" + DSML_BLOCK)
                    : textResponse("the parsed answer");
        }
        if (body.contains("TOOLCALL ONLY")) {
            // the model insists on tools: the loop must give up with a note, never an exception
            return textResponse(DSML_BLOCK);
        }
        if (body.contains("TRUNCATED FIRST")) {
            // blank first answer that spent tokens (thinking eaten the budget): retry, then succeed
            return request == 1 ? textResponse("") : textResponse("hello there");
        }
        if (body.contains("EMPTY PLEASE")) {
            // always blank: retry once, then fall back to a note
            return textResponse("");
        }
        return textResponse("hello there");
    }

    private String catPath() {
        // a real temp file so the plan-mode `cat` rule actually reads something
        try {
            final var file = storageHome.resolve("cat-target.txt");
            Files.writeString(file, "cat content");
            return file.toString();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String textResponse(final String content) {
        return "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + content.replace("\n", "\\n").replace("\"", "\\\"")
                + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    }

    private static String toolResponse(final String name, final String arguments) {
        return "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"" + arguments.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    }

    private Map<String, String> config(final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put(
                "yca.provider.providers.0.baseUrl",
                "http://localhost:" + server.getAddress().getPort() + "/v1");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.llm.retriesMaxAttempts", "1");
        entries.put("yca.llm.retriesBaseDelayMs", "1");
        entries.putAll(extra);
        return entries;
    }

    private static void respond(
            final com.sun.net.httpserver.HttpExchange exchange,
            final int status,
            final String body,
            final String contentType) {
        try {
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void answersOutOfBandWithoutPersisting() throws Exception {
        sessionStore.replace("s1", List.of(Message.user("hi"), Message.assistant("hello", List.of())));
        final var answer = answerer.ask("what did I say?", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("hello there", answer);
        final var body = bodies.getLast();
        assertTrue(body.contains("\"model\":\"test-model\""), body);
        // the tiny default budget would starve reasoning: when not configured, it follows the provider
        assertFalse(body.contains("\"max_tokens\""), body);
        // /btw now declares the plan-mode read-only tools instead of running tool-less
        assertTrue(body.contains("\"tools\""), body);
        assertTrue(body.contains("read_file"), body);
        assertTrue(body.contains("Side question: what did I say?"), body);
        assertTrue(body.contains("hello"), body);
        assertEquals(2, sessionStore.size("s1"));
        assertEquals(1L, usageTracker.auxiliaryRequests());
        assertEquals(3L, usageTracker.auxiliaryPromptTokens());
    }

    @Test
    void boundsInheritedHistory() throws Exception {
        final var messages = new ArrayList<Message>();
        for (var i = 0; i < 250; i++) {
            messages.add(Message.user("m" + i));
        }
        sessionStore.replace("s1", messages);
        answerer(Map.of("yca.slashcommands.btw.maxHistoryMessages", "200"))
                .ask("q", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = bodies.getLast();
        assertTrue(body.contains("\"m50\""), body);
        assertTrue(!body.contains("\"m49\""), body);
        assertEquals(202, body.split("\"role\"", -1).length - 1, body);
    }

    @Test
    void replaysPriorExchangesAndHonorsRecallZero() throws Exception {
        sessionStore.replace("s1", List.of());
        final var replaying = answerer(Map.of());
        replaying
                .ask("first question", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        replaying
                .ask("second question", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = bodies.getLast();
        assertTrue(body.contains("Q: first question"), body);
        assertTrue(body.contains("A: hello there"), body);
        assertTrue(body.contains("Side question: second question"), body);

        bodies.clear();
        final var noRecall = answerer(Map.of("yca.slashcommands.btw.recall", "0"));
        noRecall.ask("first question", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        noRecall.ask("second question", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(!bodies.getLast().contains("Earlier side questions"), bodies.getLast());
    }

    @Test
    void modelOverrideApplies() throws Exception {
        answerer(Map.of("yca.slashcommands.btw.model", "btw-model"))
                .ask("q", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(bodies.getLast().contains("\"model\":\"btw-model\""), bodies.getLast());
    }

    @Test
    void nativeToolCallsRoundTrip() throws Exception {
        sessionStore.replace("s1", List.of(Message.user("hi")));
        final var answer = answerer.ask("NATIVE READ", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("read round-trip done", answer);
        assertEquals(2, bodies.size(), bodies.toString());
        // the first request declared the tools, the second carried the tool result back to the model
        assertTrue(bodies.get(0).contains("\"tools\""), bodies.get(0));
        assertTrue(bodies.get(1).contains("file content: src/App.java"), bodies.get(1));
        // a round-trip still does not touch the session store
        assertEquals(1, sessionStore.size("s1"));
        assertEquals(2L, usageTracker.auxiliaryRequests());
    }

    @Test
    void toolCallMarkupIsParsedAndExecuted() throws Exception {
        sessionStore.replace("s1", List.of(Message.user("hi")));
        final var answer = answerer.ask("TOOLCALL MARKUP", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("the parsed answer", answer);
        // the DSML block was turned into a real read_file execution, not just stripped
        assertEquals(2, bodies.size(), bodies.toString());
        assertTrue(bodies.get(1).contains("file content: src/App.java"), bodies.get(1));
        assertEquals(1, sessionStore.size("s1"));
    }

    @Test
    void bashReadCommandRunsButDestructiveIsRefused() throws Exception {
        sessionStore.replace("s1", List.of(Message.user("hi")));
        final var answer = answerer(Map.of())
                .ask("BASH CAT", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("cat done", answer);
        assertEquals(2, bodies.size(), bodies.toString());
        // the plan-mode allow-list ran `cat` and fed the output back
        assertTrue(bodies.get(1).contains("cat content"), bodies.get(1));
        bodies.clear();

        // a fresh answerer so the previous exchange is not replayed into this side question
        final var denied = answerer(Map.of())
                .ask("BASH RM", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("rm done", denied);
        assertEquals(2, bodies.size(), bodies.toString());
        // `rm -rf` is not a plan-mode read-only command: it is refused, and the tool never ran
        assertTrue(bodies.get(1).contains("Permission denied by user"), bodies.get(1));
        assertFalse(bodies.get(1).contains("cat content"), bodies.get(1));
    }

    @Test
    void nonReadToolIsRefusedWithoutRunning() throws Exception {
        final var answer = answerer.ask("WRITE TOOL", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("write done", answer);
        assertEquals(2, bodies.size(), bodies.toString());
        // write_file is never declared (-tools- has no writer) and is refused by plan-mode rules
        assertFalse(bodies.get(0).contains("write_file"), bodies.get(0));
        assertTrue(bodies.get(1).contains("Permission denied by user"), bodies.get(1));
    }

    @Test
    void toolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayed() throws Exception {
        sessionStore.replace("s1", List.of());
        final var answerer = answerer(Map.of());
        final var answer = answerer.ask("TOOLCALL ONLY", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(answer.startsWith("> "), answer);
        assertTrue(answer.contains(DSML_BLOCK), answer);
        // the round cap was reached: six model round-trips, all tool-only
        assertEquals(6, bodies.size(), bodies.toString());
        // a broken exchange is never replayed into the next side question
        answerer.ask("TOOLCALL MARKUP", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = bodies.getLast();
        assertTrue(!body.contains("Q: TOOLCALL ONLY"), body);
        assertTrue(body.contains("Side question: TOOLCALL MARKUP"), body);
    }

    @Test
    void blankAnswerRetriesOnceThenReturnsNote() throws Exception {
        final var first = answerer(Map.of())
                .ask("TRUNCATED FIRST", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("hello there", first);
        // the blank first answer was retried, then succeeded
        assertEquals(2, bodies.size(), bodies.toString());

        // a fresh answerer so the previous exchange is not replayed into this side question
        bodies.clear();
        final var alwaysBlank = answerer(Map.of())
                .ask("EMPTY PLEASE", "s1", usageTracker, () -> false, null)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        // a still-blank answer becomes a note, never an exception
        assertTrue(alwaysBlank.startsWith("The model returned an empty answer"), alwaysBlank);
        assertTrue(alwaysBlank.contains("Check the output budget"), alwaysBlank);
    }
}
