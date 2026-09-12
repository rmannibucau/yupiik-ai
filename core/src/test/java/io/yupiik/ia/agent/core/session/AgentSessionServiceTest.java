package io.yupiik.ia.agent.core.session;

import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionServiceTest {

    @Test
    void toolsListsAndGroupsByServer() {
        final var service = service(new McpManager() {
            @Override
            public Map<String, List<EmbeddedTool>> toolsByServer() {
                return Map.of(
                        "fs", List.of(tool("read_file", "[fs] Read a file\n\nlong"), tool("write_file", "[fs] Write a file")),
                        "code", List.of(tool("find_symbol", "[code] Find a symbol")));
            }
        });

        final var listing = service.tools("");

        assertTrue(listing.anyTool());
        assertEquals(3, listing.matches());
        assertEquals(List.of("code", "fs"), listing.servers().stream().map(AgentSessionService.ServerTools::server).toList());
        final var fs = listing.servers().stream().filter(s -> s.server().equals("fs")).findFirst().orElseThrow();
        assertEquals(List.of("read_file", "write_file"), fs.tools().stream().map(AgentSessionService.ToolInfo::name).toList());
        assertEquals("Read a file", fs.tools().get(0).description());
    }

    @Test
    void toolsFiltersByServerAndToolKey() {
        final var service = service(new McpManager() {
            @Override
            public Map<String, List<EmbeddedTool>> toolsByServer() {
                return Map.of("fs", List.of(tool("read_file", "Read a file"), tool("write_file", "Write a file")));
            }
        });

        assertEquals(1, service.tools("fs read").matches());
        assertEquals(0, service.tools("nomatch").matches());
        final var filtered = service.tools("write");
        assertEquals(1, filtered.servers().get(0).tools().size());
        assertEquals("write_file", filtered.servers().get(0).tools().get(0).name());
    }

    @Test
    void toolsNoToolsYieldEmptyListing() {
        final var service = service(new McpManager() {
            @Override
            public Map<String, List<EmbeddedTool>> toolsByServer() {
                return Map.of();
            }
        });

        final var listing = service.tools("");
        assertFalse(listing.anyTool());
        assertTrue(listing.servers().isEmpty());
    }

    @Test
    void permissionsParseAllNoneAndInvalid() {
        final var service = service(new PermissionService(cfg()));

        assertEquals(Boolean.TRUE, service.setPermissions("all"));
        assertEquals(Boolean.TRUE, service.setPermissions("unsafe"));
        assertEquals(Boolean.FALSE, service.setPermissions("none"));
        assertEquals(Boolean.FALSE, service.setPermissions("safe"));
        assertNull(service.setPermissions(""));
        assertNull(service.setPermissions("bogus"));
    }

    @Test
    void setPermissionsUpdatesService() {
        final var permissions = new PermissionService(cfg());
        final var service = service(permissions);

        assertEquals(Boolean.TRUE, service.setPermissions("all"));
        assertTrue(permissions.isPermitAll());
        assertEquals(Boolean.FALSE, service.setPermissions("none"));
        assertFalse(permissions.isPermitAll());
    }

    @Test
    void permissionsInfoExposesStateAndSessionGrants() {
        final var permissions = new PermissionService(cfg());
        permissions.setPermitAll(true);
        permissions.grantSession("bash", Map.of("command", "mvn install"));
        final var service = service(permissions);

        final var info = service.permissionsInfo();
        assertTrue(info.permitAll());
        assertFalse(info.sessionGrants().isEmpty());
        assertTrue(info.sessionGrants().stream().anyMatch(g -> g.contains("|bash:mvn")));
    }

    @Test
    void setModelStripsDollarPrefix() {
        final var client = chatClient();
        final var service = service(client);

        assertEquals("my-model", service.setModel("$my-model"));
        assertEquals("my-model", client.model());
    }

    @Test
    void agentOverrideResolvesKnownAndKeepsUnknownAndPlainText() {
        final var service = service(agentProvider("coder"), agentsConfig("coder"));

        final var known = service.agentOverride("@coder do the thing");
        assertEquals("coder", known.agentName());
        assertEquals("do the thing", known.prompt());

        final var unknown = service.agentOverride("@nope do the thing");
        assertNull(unknown.agentName());
        assertEquals("@nope do the thing", unknown.prompt());

        final var noSpace = service.agentOverride("@coder");
        assertNull(noSpace.agentName());
        assertEquals("@coder", noSpace.prompt());

        final var plain = service.agentOverride("plain prompt");
        assertNull(plain.agentName());
        assertEquals("plain prompt", plain.prompt());
    }

    @Test
    void setAgentRejectsUnknown() {
        final var service = service(agentProvider("coder"), agentsConfig("coder"));

        assertEquals("coder", service.setAgent("coder"));
        assertNull(service.setAgent("missing"));
    }

    @Test
    void firstLineStatic() {
        assertEquals("", AgentSessionService.firstLine(null));
        assertEquals("", AgentSessionService.firstLine(""));
        assertEquals("first", AgentSessionService.firstLine("first\nsecond"));
        assertEquals("no newline", AgentSessionService.firstLine("no newline"));
        assertEquals("first line", AgentSessionService.firstLine("first line\nsecond line\nthird"));
    }

    @Test
    void exportPlanErrorsOutsidePlanMode() {
        final var service = service(new PermissionService(cfg()));

        final var export = service.exportPlan("s1", "", "plan");

        assertNotNull(export.error());
        assertTrue(export.error().contains("plan mode"));
    }

    @Test
    void exportPlanErrorsWhenNoPlanYet() {
        final var permissions = new PermissionService(cfg());
        permissions.setMode(AgentMode.PLAN);
        final var seeder = new PlanTodoSeeder() {
            @Override
            public String plan(final String sessionId) {
                return null;
            }
        };
        final var service = service(permissions, seeder, new PlanFileService(null, null), null, cfg());

        final var export = service.exportPlan("s1", "", "plan");

        assertNotNull(export.error());
        assertTrue(export.error().contains("no plan"));
    }

    @Test
    void exportPlanWritesPathInPlanMode() throws Exception {
        final var dir = Files.createTempDirectory("agent-session-test");
        final var permissions = new PermissionService(cfg());
        permissions.setMode(AgentMode.PLAN);
        final var seeder = new PlanTodoSeeder() {
            @Override
            public String plan(final String sessionId) {
                return "the plan content";
            }
        };
        final var fileService = new PlanFileService(null, null) {
            @Override
            public Path defaultPath(final Path storageHome, final String title, final String sessionId) {
                return storageHome.resolve(title + ".plan.md");
            }

            @Override
            public String render(final String plan, final List<TodoItem> todos) {
                return plan + "\n\n## Todos\n" + todos.size();
            }
        };
        final var config = cfg(dir.toString(), AgentMode.PLAN);
        final var todoStore = new TodoStore() {
            @Override
            public List<TodoItem> all() {
                return List.of(new TodoItem("task one", "pending"));
            }
        };
        final var service = service(permissions, seeder, fileService, todoStore, config);

        final var export = service.exportPlan("s1", "", "my title");

        assertNull(export.error());
        assertNotNull(export.path());
        assertTrue(Files.isRegularFile(export.path()));
        assertEquals(dir.resolve("my title.plan.md"), export.path());
        assertEquals("the plan content\n\n## Todos\n1", export.original());
    }

    @Test
    void reloadPlanReportsUnchangedAndChanged() throws Exception {
        final var dir = Files.createTempDirectory("agent-session-test");
        final var planPath = dir.resolve("plan.md");
        Files.writeString(planPath, "same content");
        final var fileService = new PlanFileService(null, null) {
            @Override
            public PlanFileService.PlanFile parse(final String content) {
                return new PlanFileService.PlanFile(content, List.of(new TodoItem("a task", "pending")));
            }

            @Override
            public String reload(final String sessionId, final PlanFileService.PlanFile parsed) {
                return "plan reloaded with " + parsed.todos().size() + " tasks";
            }
        };
        final var service = service(fileService, cfg(dir.toString(), null));

        final var unchanged = service.reloadPlan("s1", planPath, "same content");
        assertFalse(unchanged.changed());
        assertNull(unchanged.error());
        assertEquals("no changes in the plan (nothing to reload)", unchanged.message());

        Files.writeString(planPath, "edited content");
        final var changed = service.reloadPlan("s1", planPath, "same content");
        assertTrue(changed.changed());
        assertNull(changed.error());
        assertEquals("plan reloaded with 1 tasks", changed.message());
    }

    @Test
    void reloadPlanMissingFileReportsError() {
        final var service = service(new PlanFileService(null, null), cfg());

        final var reload = service.reloadPlan("s1", Path.of("/no/such/plan.md"), "original");

        assertNotNull(reload.error());
        assertTrue(reload.error().contains("plan file not found"));
    }

    @Test
    void memoryReadsProjectAndGlobal() {
        final var memoryStore = new MemoryStore() {
            @Override
            public String readGlobal() {
                return "global notes";
            }

            @Override
            public String readProject() {
                return "project notes";
            }
        };
        final var service = service(memoryStore);

        final var memory = service.memory();

        assertEquals("global notes", memory.global());
        assertEquals("project notes", memory.project());
    }

    @Test
    void compactReturnsNullForEmptyHistory() {
        final var store = new SessionStore() {
            @Override
            public List<Message> load(final String id) {
                return List.of();
            }
        };
        final var service = service(store);

        final var result = service.compact("s1");
        assertNotNull(result);
        assertFalse(result.applied());
        assertEquals("nothing to compact", result.message());
    }

    @Test
    void sessionInfoReportsIdAndPath() {
        final var service = service(new SessionStore() {
            @Override
            public Path path(final String id) {
                return Path.of("/sessions/" + id + ".jsonl");
            }
        });

        final var info = service.sessionInfo("s1");
        assertEquals("s1", info.id());
        assertEquals(Path.of("/sessions/s1.jsonl"), info.path());
    }

    @Test
    void flushRewritesLoadedTranscript() {
        final var rewritten = new boolean[1];
        final var store = new SessionStore() {
            @Override
            public List<Message> load(final String id) {
                return List.of(Message.user("hi"));
            }

            @Override
            public void replace(final String id, final List<Message> messages) {
                rewritten[0] = true;
            }
        };
        final var service = service(store);

        assertEquals("session flushed", service.flush("s1"));
        assertTrue(rewritten[0]);
    }

    @Test
    void flushReportsNothingForEmptyHistory() {
        final var service = service(new SessionStore() {
            @Override
            public List<Message> load(final String id) {
                return List.of();
            }
        });

        assertEquals("nothing to flush", service.flush("s1"));
    }

    @Test
    void housekeepingDelegatesToStoreWithEmptyDeletion() {
        final var store = new SessionStore() {
            @Override
            public SessionStore.HousekeepingReport housekeeping(final boolean deleteEmpty) {
                return new SessionStore.HousekeepingReport(2, 3, 4);
            }
        };
        final var service = service(store);

        final var report = service.housekeeping();
        assertEquals(2, report.compressed());
        assertEquals(3, report.deleted());
        assertEquals(4, report.kept());
    }

    @Test
    void resyncGraphPropagatesFailure() {
        final var graph = new StructuralCodeGraph() {
            @Override
            public String resync() {
                throw new IllegalStateException("boom");
            }
        };
        final var service = service(graph);

        assertThrows(RuntimeException.class, service::resyncGraph);
    }

    private static AgentSessionService service(final PermissionService permissions) {
        return new AgentSessionService(permissions, null, null, null, null, null, null, null, null, null, null, null, cfg(), null);
    }

    private static AgentSessionService service(final McpManager mcpManager) {
        return new AgentSessionService(null, null, mcpManager, null, null, null, null, null, null, null, null, null, cfg(), null);
    }

    private static AgentSessionService service(final ChatClient chatClient) {
        return new AgentSessionService(null, chatClient, null, null, null, null, null, null, null, null, null, null, cfg(), null);
    }

    private static AgentSessionService service(final AgentProvider agentProvider, final CodingAgentConfiguration config) {
        return new AgentSessionService(null, null, null, agentProvider, null, null, null, null, null, null, null, null, config, null);
    }

    private static AgentSessionService service(final SessionStore store) {
        return new AgentSessionService(null, null, null, null, store, null, null, null, null, null, null, null, cfg(), null);
    }

    private static AgentSessionService service(final MemoryStore memoryStore) {
        return new AgentSessionService(null, null, null, null, null, null, null, memoryStore, null, null, null, null, cfg(), null);
    }

    private static AgentSessionService service(final StructuralCodeGraph graph) {
        return new AgentSessionService(null, null, null, null, null, null, null, null, graph, null, null, null, cfg(), null);
    }

    private static AgentSessionService service(final PlanFileService planFileService, final CodingAgentConfiguration config) {
        return new AgentSessionService(null, null, null, null, null, null, null, null, null, planFileService, null, null, config, null);
    }

    private static AgentSessionService service(final PermissionService permissions, final PlanTodoSeeder seeder,
                                               final PlanFileService planFileService, final TodoStore todoStore,
                                               final CodingAgentConfiguration config) {
        return new AgentSessionService(permissions, null, null, null, null, null, null, null, null, planFileService, seeder, todoStore, config, null);
    }

    private static CodingAgentConfiguration cfg() {
        return config(null, null);
    }

    private static CodingAgentConfiguration cfg(final String storageHome, final AgentMode mode) {
        return config(storageHome, mode);
    }

    private static CodingAgentConfiguration config(final String storageHome, final AgentMode mode) {
        return new CodingAgentConfiguration(
                storageHome, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, mode,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig());
    }

    private static CodingAgentConfiguration agentsConfig(final String... names) {
        final var agents = java.util.Arrays.stream(names)
                .map(n -> new AgentConfiguration(n, "<prompt>"))
                .toList();
        return new CodingAgentConfiguration(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, agents, null,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig());
    }

    private static AgentProvider agentProvider(final String... names) {
        return new AgentProvider(agentsConfig(names));
    }

    private static ChatClient chatClient() {
        return new ChatClient() {
            private String model;

            @Override
            public void setModel(final String model) {
                this.model = model;
            }

            @Override
            public String model() {
                return model;
            }
        };
    }

    private static EmbeddedTool tool(final String name, final String description) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
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
            public ToolResult execute(final Map<String, Object> args) {
                return ToolResult.ok(null);
            }
        };
    }
}