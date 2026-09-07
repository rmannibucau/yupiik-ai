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

## Requirements

- Java 25+ (26 works), Maven 4.x
- An OpenAI compatible endpoint (base URL + API key)

## Build

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.2+1.1-zulu
mvn clean install
```

The runnable artifact is the fatjar: `tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar`.

## Run

The terminal backend (jline) reads the terminal through the FFM API, so the JVM
must be started with `--enable-native-access=ALL-UNNAMED` (otherwise a warning
is printed and terminal handling can degrade):

```bash
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar tui
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "explain the pom.xml" --yca-provider-model gpt-5-mini
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "apply the fix" --yca-provider-model gpt-5-mini --yca-run-permitAll
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar help
```

`--yca-run-permitAll` (and `--yca-tui-permitAll` for the `tui` command) bypass
permission prompts and allow every tool call without asking.

Minimal configuration (defaults to `https://api.openai.com/v1`):

```bash
export OPENAI_API_KEY=sk-...
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "list the files" --yca-provider-model gpt-5-mini
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
      "baseUrl": "https://api.openai.com/v1",
      "apiKey": "",
      "model": "gpt-5-mini",
      "api": "auto",
      "contextWindow": 128000
    },
    "permissions": { "mode": "ask", "allow": [], "deny": ["bash:rm -rf *"] },
    "mcp": {
      "servers": [
        { "name": "docs", "command": "npx", "args": ["-y", "@upstreamapi/context7-mcp"], "readonly": ["*"] },
        { "name": "remote", "url": "https://example.com/mcp", "apiKey": "...", "readonly": [] }
      ]
    },
    "mode": "build",
    "model": { "compact": "", "prune": "" }
  }
}
```

Main groups: `provider` (baseUrl, apiKey, model, api, reasoningField,
contextWindow, timeoutSeconds), `llm` (retries, rpm throttle, maxConcurrent),
`compaction` (thresholdPercent, enabled), `pruning` (thresholdPercent,
keepRecent, toolOutputMaxChars), `memory` (enabled, maxChars), `optimize`
(disabled processors), `permissions` (mode, allow, deny, planBash), `sessions`
(retentionDays, compressDays), `mode` (build or plan), `skills` (enabled),
`mcp` (servers). The `workingDir` option (default `.`) is the directory from
which project instructions and skills are discovered. UI colors (under `tui`,
e.g. `permitAll` for the red highlight shown when permissions are set to
`all`) are themeable hex values.

Each MCP server entry accepts a `readonly` list of tool names that stay
available (read-only) in `plan` mode; `"*"` makes every tool of that server
read-only. In `plan` mode only the `READ`/`AGENT` tools, these read-only MCP
tools, and `bash` commands starting with one of the `permissions.planBash`
prefixes (defaults to read-only commands like `git status`, `git diff`,
`ls`, `cat`, `grep`...) are allowed - everything else is denied. `build` mode
(the default) has full access and respects the `permissions` allow/deny lists.

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
- Slash commands: `/help`, `/shortcuts`, `/new`, `/resume`, `/compact`, `/model [$name]` (list, or set the model - `Tab` completes the name), `/mode build|plan`, `/memory`, `/context [path]` (show the resolved context, write it to path when given), `/permissions all|none` (allow every tool without asking, or restore interactive approval), `/clear`, `/quit`
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
