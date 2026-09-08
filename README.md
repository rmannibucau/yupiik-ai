# yca - yupiik coding agent

`yca` is an opencode-like coding agent for the terminal, built on the
[Yupiik Fusion](https://github.com/yupiik/fusion) framework and
[TamboUI](https://tamboui.dev) with an OpenAI compatible API backend
(chat completions and responses flavors, auto-detected).

## Features

- Interactive TUI (`tui` command) and headless mode (`run --prompt "..."`)
- Streaming output with reasoning/thinking support
- Built-in tools: read/write/edit files, ls, glob, grep, bash, todo list, memory write
- Built-in tools are exposed as a local MCP server; remote MCP servers (stdio and
  streamable HTTP) are supported and merged into the same tool registry
- rtk-style output optimization: maven, gradle, git, gh, npm and prettier outputs
  are compacted before entering the model context (pluggable `OutputProcessor` SPI)
- Context management: usage gauges, threshold-based pruning and compaction
  (pruning/compaction can use a dedicated smaller model)
- Sessions (JSONL) with resume, todo list, global and per-project auto memory
- Permission modes: `ask`, `auto_edit`, `yolo` plus allow/deny glob lists
- Plan/build access modes: `build` uses every tool and any action, `plan` is
  read-only (read tools plus MCP tools whitelisted as read-only); switchable at
  runtime with `/mode`
- MCP server health shown in the TUI sidebar with status dots (enabled / disabled / failing)
- Rate limiting, retries and concurrency limits for LLM calls
- Custom agents (`yca.agents`): named prompts injected on top of the base system
  prompt, selected with `--agent` on `run`, `/agent` in the TUI, or a `@<name>`
  prompt prefix for a single turn

## Requirements

- Java 25+ (26 works), Maven 4.x
- An OpenAI compatible endpoint (base URL + API key)
- A JDK (not just a JRE) when the structural code graph is enabled: its Java
  parser uses the `javac` API, so it cannot run on a headless JRE.

## Build

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.2+1.1-zulu
mvn clean install
```

The runnable artifact is the fatjar: `tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar`.

## Run

The terminal backend (jline) reads the terminal through the FFM API, so the JVM
must be started with `--enable-native-access=ALL-UNNAMED` (otherwise a warning
is printed and terminal handling can degrade).

```bash
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar tui
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "explain the pom.xml" --provider-model gpt-5-mini
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "apply the fix" --yca-provider-model gpt-5-mini --yca-run-permitAll
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar help
```

`--yca-run-permitAll` (and `--yca-tui-permitAll` for the `tui` command) bypass
permission prompts and allow every tool call without asking.

Argument keys may be given with or without the `yca.` prefix, so both
`--provider-model` and `--yca-provider-model` work.

Minimal configuration (defaults to `https://api.openai.com/v1`):

```bash
export OPENAI_API_KEY=sk-...
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "list the files" --provider-model gpt-5-mini
```

Scaffold a new project in a fresh directory (behaves like a neutral
scaffolder: no code graph indexed over an unrelated tree, no unrelated repo
README/AGENTS.md injected):

```bash
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar \
  run --prompt "generate a frontend project for yupiik fusion in ./frontend" \
      --project-dir ./frontend
```

## Configuration

Configuration is resolved from (highest priority first): CLI arguments
(`--yca-<key> <value>`), then `~/.yupiik/coding-agent/configuration.json` (or the
`yca.configuration.json` key), system properties and environment variables
(`YCA_PROVIDER_BASEURL` style).

```json
{
  "yca": {
    "provider": {
      "providers": [
        {
          "id": "openai",
          "baseUrl": "https://api.openai.com/v1",
          "apiKey": "",
          "model": "gpt-5-mini",
          "api": "auto",
          "contextWindow": 128000
        },
        {
          "id": "ollama",
          "baseUrl": "http://localhost:11434/v1",
          "model": "qwen3-coder",
          "providerSpecificOptions": "{\"options\":{\"temperature\":0.1}}"
        }
      ],
      "defaultProvider": "openai"
    },
    "permissions": { "mode": "ask", "allow": [], "deny": ["bash:rm -rf *"] },
    "mcp": {
      "servers": [
        { "name": "docs", "command": "npx", "args": ["-y", "@upstreamapi/context7-mcp"], "readonly": ["*"] },
        { "name": "remote", "url": "https://example.com/mcp", "apiKey": "...", "readonly": [] }
      ]
    },
    "mode": "build",
    "model": { "compact": "", "prune": "" },
    "agents": [
      { "name": "reviewer", "prompt": "~/.yupiik/coding-agent/agents/reviewer.md" },
      { "name": "tester", "prompt": "You are a meticulous test author. Prefer table-driven tests." }
    ],
    "structuralCodeGraph": { "enabled": true, "enrich": false }
  }
}
```

The `provider` group can hold a list of providers (`providers`), each with a
unique `id` used to reference it as `$<id>/<model>` anywhere a model is
expected (main model, `model.compact`, `model.prune`, code graph enrichment).
`defaultProvider` selects the active one; when it is blank it falls back to the
first provider (or to the implicit `default` provider). For single-provider
setups the legacy flat scalars (`baseUrl`, `apiKey`, `model`, `api`,
`reasoningField`, `contextWindow`, `timeoutSeconds`) are still accepted and
build the implicit `default` provider. A provider's optional
`providerSpecificOptions` (a JSON object, empty by default) is merged into
every API call payload for that provider (e.g. `variant` or thinking effort).

Main groups: `provider` (providers, defaultProvider, baseUrl, apiKey, model,
api, reasoningField, contextWindow, timeoutSeconds, providerSpecificOptions),
`llm` (retries, rpm throttle, maxConcurrent, retryOnContextOverflow),
`compaction` (thresholdPercent, enabled), `pruning` (thresholdPercent,
keepRecent, toolOutputMaxChars), `memory` (enabled, maxChars), `optimize`
(disabled processors), `permissions` (mode, allow, deny, planBash), `sessions`
(retentionDays, compressDays), `mode` (build or plan), `skills` (enabled),
`mcp` (servers), `structuralCodeGraph` (enabled, enrich, storeDir), `agents`
(name, prompt - inline text or a file path). The
`workingDir` option (default `.`) is the directory from
which project instructions and skills are discovered. The `projectDir` option
(defaults to `workingDir`) is the target project directory a task is about -
where generated/edited files belong. When `projectDir` points at a fresh or
empty directory (e.g. scaffolding a new project), the agent behaves like a
neutral scaffolder: it does not index an unrelated structural code graph and
does not inject an unrelated repository README/AGENTS.md, scoping to the
target directory the way a tool like opencode does. UI colors (under `tui`,
e.g. `permitAll` for the red highlight shown when permissions are set to
`all`) are themeable hex values.

Context usage is estimated locally against `provider.contextWindow` and,
before each turn, the conversation is pruned at `pruning.thresholdPercent`
(default 65%) and compacted at `compaction.thresholdPercent` (default 80%).
If, despite this, the model provider rejects the request as exceeding its
real context window, the agent (when `llm.retryOnContextOverflow`, default
true) compacts the context once, reports "context window exceeded; compacting
and retrying", and retries the turn before falling back to an error.

Each MCP server entry accepts a `readonly` list of tool names that stay
available (read-only) in `plan` mode; `"*"` makes every tool of that server
read-only. In `plan` mode only the `READ`/`AGENT` tools, these read-only MCP
tools, and `bash` commands starting with one of the `permissions.planBash`
prefixes (defaults to read-only commands like `git status`, `git diff`,
`ls`, `cat`, `grep`...) are allowed - everything else is denied. `build` mode
(the default) has full access and respects the `permissions` allow/deny lists.

## Structural code graph

The agent builds a deterministic symbol/call index of the project and exposes it
through the `code_*` tools so the model can reason about the codebase without a
large text dump. It runs at startup (warm-up), refreshes incrementally when a
file is written or edited, and patches only the drifted/missing files afterwards
based on per-file content hashes.

- **Requires a JDK**: the Java parser uses the `javac` compiler API, so it does
  not run on a plain JRE. No extra JVM flags are needed.
- **Deterministic, no LLM by default**: the structural index (types, methods,
  fields, imports, extends/implements, calls) is extracted from the parse tree
  without any model call. Optional LLM *concept nodes* (per-subsystem Markdown
  summaries) can be enabled with `yca.structural-code-graph.enrich`.
- **Cache**: the index is stored per-project under `workingDir/.yca/generated/graph/`
  (`graph.json` + optional `nodes/*.md`) and is regenerable, so it is git-ignored.
- **Tools**: `code_find_code`, `code_file_api`, `code_trace_calls`,
  `code_find_all`, `code_repo_map`, `code_check_graph`. All are read-only and
  therefore available in `plan` mode too.

Configuration (`yca.structural-code-graph.*`): `enabled` (default `true`),
`enrich` (default `false`), `baseUrl`/`apiKey`/`model` (optional cheaper model
override for enrichment, falling back to the main provider), `maxFileBytes`
(default 5242880), `languageExtensions` (default `["java"]`), `storeDir`
(default `.yca/generated/graph`). Setting `enabled=false` disables the index
entirely.

Storage lives under `~/.yupiik/coding-agent` (`sessions/`, `memory/`,
`todos.json`); project memory is `.yca/memory/MEMORY.md`. At startup sessions
inactive longer than `sessions.retentionDays` (default 30) are dropped and
those inactive longer than `sessions.compressDays` (default 15) are gzip
compressed; a compressed session is decompressed back to JSONL on resume (the
`.jsonl.gz` is then removed). Set a value to `0` to disable the corresponding
cleanup.

Project instructions and skills are loaded into the system prompt:

- **AGENTS.md**: every `AGENTS.md` found walking up from `workingDir` to the
  filesystem root is included (root first, deepest rules last), followed by the
  global `AGENTS.md` in the storage home plus opencode/claude globals
  (`~/.config/opencode/AGENTS.md`, `~/.claude/AGENTS.md`). `CLAUDE.md` and
  `README.md` are read from `workingDir` too.
- **Skills** (`skills.enabled`, default true): `<name>/SKILL.md` is discovered
  under the project `.opencode/skills`, `.claude/skills` and `.yca/skills`
  directories (walked up the tree) and the global storage-home `skills/` plus
  `~/.config/opencode/skills` and `~/.claude/skills`. YAML frontmatter
  (`name:`/`description:`) is parsed and each skill is injected as a `<skill>`
  section.

## TUI

- Right details panel: model, flavor, cwd, git branch, mode, session, MCP
  servers with status dots (green=enabled, grey=disabled, red=failing), token
  counts and a context gauge
- Transcript and prompt have an accent-colored left bar; transcript renders as
  markdown (thinking shown dimmed, toggle with `Ctrl+T`). When permissions are
  set to `all` the left bar, mode line and status turn red
  (`tui.permitAll`, default `#ff5555`)
- `Ctrl+P` cycles the todos panel: open -> collapsed -> hidden
- `Ctrl+Y` shows/hides executed tool actions (command names and streamed output)
- `Ctrl+S` compact, `Ctrl+L` clear, `Ctrl+C` quit, `Esc` interrupt
- While a tool runs, a line under the input shows `calling <command>` with an
  animated spinner (and `esc to interrupt` on the right) and its output is
  streamed live into the transcript
- Code blocks in the transcript get basic syntax highlighting (java, ts, python,
  shell, sql, xml/json, plus a generic fallback)
- Slash commands: `/help`, `/shortcuts`, `/new`, `/resume`, `/compact`, `/model [$name]` (list, or set the model - `Tab` completes the name), `/mode build|plan`, `/agent [name]` (list custom agents, or set the active agent - `Tab` completes the name), `/memory`, `/context [path]` (show the resolved context, write it to path when given), `/permissions all|none` (allow every tool without asking, or restore interactive approval), `/clear`, `/quit`
- Tool approvals use a modal (respecting the configured permission mode)

## Modules

- `core`: configuration, OpenAI compatible client, tools, MCP, context management,
  optimization SPI, sessions, permissions, memory
- `tui`: `tui` (TamboUI interface) and `run` (headless) commands, fatjar packaging
- `documentation`: minisite documentation (CLI and configuration generated by
  fusion-documentation)

## Documentation

```bash
# generate the site (target/documentation-1.0.0-SNAPSHOT)
mvn -pl documentation io.yupiik.maven:yupiik-tools-maven-plugin:1.2.14:minisite

# or serve it live on http://localhost:4200
mvn -pl documentation io.yupiik.maven:yupiik-tools-maven-plugin:1.2.14:serve-minisite
```

CLI (`cli/*.adoc`) and configuration (`content/_partials/generated/*.adoc`)
pages are generated by fusion-documentation before rendering.

Generated content lands in `documentation/target/minisite/site/`.
