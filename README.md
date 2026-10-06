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
- Plan/build access modes: `plan` is the default and is read-only (read tools plus
  MCP tools whitelisted as read-only); `build` uses every tool and any action.
  Switchable at runtime with `/mode` (or `--yca-run-mode` for `run`). In `plan`
  mode the plan steps are recorded with `write_todo` as pending todos, and
  switching back to `build` seeds the todo list from the last plan when the model
  did not record it itself
- MCP server health shown in the TUI sidebar with status dots (enabled / disabled / failing)
- Rate limiting, retries and concurrency limits for LLM calls, shared per
  provider across tabs: the resilient LLM client (throttle, max-concurrent and
  retry queue) is keyed by (provider, flavor), so several tabs calling the same
  provider share one pool - the provider's announced `concurrency_limit_exceeded`
  adaptation is surfaced once per limit, not on every 429
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
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar tui --session <session-id>
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "explain the pom.xml" --provider-model gpt-5-mini
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "produce a plan to add auth" --provider-model gpt-5-mini --yca-run-mode plan
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar run --prompt "apply the fix" --yca-provider-model gpt-5-mini --yca-run-mode build --yca-run-show-tool-output
java --enable-native-access=ALL-UNNAMED -jar tui/target/tui-1.0.0-SNAPSHOT-fatjar.jar help
```

`tui` and `cli` accept a repeatable `--initialPrompt` option (comma separated
values): each value is submitted at startup, in order, exactly as if it had been
typed, then the app returns to normal input. It is handy for scripts and soft
resumes: `--initialPrompt continue` on a resumed session (`--session <session-id>`)
re-runs the agent on the persisted history without sending any new message
(soft resume, honoring the same resume aliases as typing `continue`). Prefix a
value with `sep=<char>,` to split on another single character when a value
itself contains commas.

Plan mode is the default for every command. `run` is read-only by default; pass
`--yca-run-mode build` to allow applying changes. `--yca-run-show-tool-output` controls tool output presentation (shown, collapsed
or hidden; booleans accepted for backward compatibility).

The `cli` command gets full line editing when the fatjar is used: Up/Down browse
the in-memory history, Alt-b/Alt-f move by word, Alt-Backspace and Ctrl-W delete
a word, Ctrl-A/Ctrl-E/Ctrl-U/Ctrl-K edit the line, Left/Right/Delete move and
Ctrl-L clears the screen. `Tab` completes slash command names and their
arguments (models, providers, agents, sessions, tools, options and paths).
`/shortcuts` lists the active keys. `Ctrl-C` opens an interrupt menu: cancel the
current turn and stay in the REPL, or exit (when idle a second press quits);
`Ctrl-D` exits too. Cancelling is cooperative but prompt: the running LLM stream
is stopped within ~100ms, in-flight `bash`/`web_fetch` tools are killed, and a
pending compaction/retry is aborted at its next checkpoint - the transcript is
not marked as an error, the streamed content stays and the turn ends cleanly
(busy state is reset, so the next prompt starts normally). In the TUI `Esc`
interrupts the same way and also drops the queued prompts and the remaining
scheduled `--initialPrompt`s so the interrupted work does not immediately
restart. When only the
`core` jar is on the classpath the prompt falls back to a plain, non-editing
`stdin` reader (Ctrl-C/Ctrl-D still work; with the plain reader, Ctrl-C while a
turn runs also opens the interrupt menu).

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
          "contextWindow": 128000,
          "models": [
            { "model": "gpt-5", "style": { "color": "#7aa2f7", "bold": "true" }, "behavior": { "reasoningEffort": "high", "outputTokens": 32000 } },
            { "model": "gpt-5-mini", "behavior": { "outputTokens": 16000 } }
          ]
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
    "httpClient": {
      "proxy": { "host": "proxy.corp", "port": 3128, "user": "alice", "password": "s3cret" }
    },
    "mcp": {
      "servers": [
        { "name": "docs", "command": "npx", "args": ["-y", "@upstreamapi/context7-mcp"], "readonly": ["*"] },
        { "name": "remote", "url": "https://example.com/mcp", "apiKey": "...", "readonly": [] }
      ]
    },
    "mode": "plan",
    "model": { "compact": "", "prune": "" },
    "agents": [
      { "name": "reviewer", "prompt": "~/.yupiik/coding-agent/agents/reviewer.md" },
      { "name": "tester", "prompt": "You are a meticulous test author. Prefer table-driven tests." }
    ],
    "structuralCodeGraph": { "enabled": true, "enrich": false }
  }
}
```

The shared HTTP client honors an optional `yca.httpClient.proxy` group
(`host`, `port` default 8080, `user`, `password`). A blank `host` keeps the
JDK default proxy selection (the `http.proxyHost`/`https.proxyHost` system
properties or the `HTTPS_PROXY`/`HTTP_PROXY` environment variables). When a
host is set, every request of the shared client goes through that proxy - LLM
calls, `webfetch`, `websearch`, MCP servers over HTTP and the maven repository
knowledge lookups. `user`/`password` enable Basic proxy authentication
(`Proxy-Authorization`, JDK `Authenticator`); note the JDK disables Basic
authentication for HTTPS CONNECT tunneling by default
(`jdk.http.auth.tunneling.disabledSchemes=Basic` in `$JAVA_HOME/conf/net.properties`),
so proxy-authenticated HTTPS needs the JVM started with
`-Djdk.http.auth.tunneling.disabledSchemes=` (plain-HTTP proxying works out of
the box).

The `provider` group can hold a list of providers (`providers`), each with a
unique `id` used to reference it as `$<id>/<model>` anywhere a model is
expected (main model, `model.compact`, `model.prune`, code graph enrichment).
`defaultProvider` selects the active one; when it is blank it falls back to the
first provider (or to the implicit `default` provider). For single-provider
setups the legacy flat scalars (`baseUrl`, `apiKey`, `model`, `api`,
`reasoningField`, `contextWindow`, `outputTokens`, `timeoutSeconds`, `color`)
are still accepted and build the implicit `default` provider. A provider's
optional `providerSpecificOptions` (a JSON object, empty by default) is merged
into every API call payload for that provider (e.g. `variant` or thinking
effort).

Each provider can declare its known models through `models`, a list of
`{ model, style, behavior }` entries. `model` is the model id (or a fragment:
matching is exact first, then by `contains`, with a `*` acting as a wildcard
ignored during the comparison - so `qwen3-coder` and `qwen*` both match
`qwen3-coder-32b`). `style` is `{ color, bold }`: `color` is an optional
`#rrggbb` tone (only the hue/chroma is taken, the shade still follows the
model position) and `bold` is an optional `"true"`/`"false"` (as a string,
so that an unset value falls back to the default). `behavior` is
`{ reasoningEffort, outputTokens, repairToolCallArguments }`: `reasoningEffort` is a free-form string
sent as `reasoning_effort` (blank means "not set") and `outputTokens` overrides
the provider `outputTokens` for that model (`0` or a negative value means "use
the provider default"). Unset fields fall back to the provider defaults
(`outputTokens`, `reasoningEffort`, `color`) and then to the implicit rules.
`reasoningEffort` is only injected when neither the request nor
`providerSpecificOptions` already sets `reasoning_effort`.
`repairToolCallArguments` (also available at the provider level under
`tuning`, eg. `"tuning": { "repairToolCallArguments": "enabled" }`) controls
the best-effort repair of tool call arguments emitted by the model before
they are exposed and stored: some OpenAI compatible backends (llama.cpp /
ollama style) reject whole turns with an HTTP 500
(`Failed to parse tool call arguments as JSON:
[json.exception.parse_error.101]`) when the arguments carry unescaped control
characters (literal tabs/newlines in string values) or a structurally corrupt
tail under a heavy context (a truncated blob or a second JSON value after the
closing brace). The repair escapes control characters and trims a trailing
second JSON value, always re-validating and never inventing values: payloads
that stay unparseable are left untouched so the corruption stays visible.
Values are `enabled` (always repair the safe cases), `disabled`
(never) and `auto` (the default): `auto` repairs for any Qwen 27B variant (the
effective model contains `qwen` and `27b`, case-insensitive) and is disabled
otherwise. The per-model `behavior` value wins over the provider `tuning`
value, which wins over `auto`.

Models are colour-coded in the CLI prompt and TUI so several models can be told
apart: the first two models of a provider are bold and use bright colors, later
ones are dimmer, and the tone rotates across a palette driven by the provider
position (the implicit flat provider is position `0`, `providers.0` is `1`, and
so on). Models used but not listed get a deterministic shade derived from their
name, and the whole scheme adjusts its lightness to the current background
(dark or light, following the persisted theme). An explicit `style.color` /
`style.bold` always wins; the resolved style can be set per model as above.
`model.prune` defaults to `model.compact` when blank, and both fall back to the
active provider model when unset. `model.memory` selects the model used by the
memory write path (extraction and rewrite): it defaults to `model.compact`, then
to the active provider model.

The session selection (provider/model/variant/sampling) travels inside each
`ChatCompletionRequest` (`request.settings()`): the execution API
(`chat`/`stream`/`chatStructured`) resolves the concrete model with
`request.model()` &gt; `settings.model()` &gt; provider default, and a
`$provider/model` request model routes the call to that provider. The selection
is core-internal: it never crosses into `llm-client` and is never serialized.

Main groups: `provider` (providers, defaultProvider, baseUrl, apiKey, model,
api, reasoningField, contextWindow, outputTokens, timeoutSeconds, color,
models, providerSpecificOptions),
`llm` (retries, rpm throttle, maxConcurrent, retryOnContextOverflow),
`compaction` (thresholdPercent, enabled, tailBudgetTokens), `pruning` (thresholdPercent,
keepRecent, toolOutputMaxChars, ingestionOutputCapChars, minPruneTokens, protectTurns,
protectRecentTokens, shrinkOutputChars, deduplicateToolCalls, supersedeOverwrittenWrites,
purgeFailedToolInputs, purgeFailedToolInputsTurns, nudgeThresholdPercent,
nudgeIterationThreshold, nudgeTemplate), `memory` (enabled, maxChars), `optimize`
(disabled processors and tools, matched loosely - `mvn`, `./mvnw`,
`git.exe` and `git` all match the git/maven processors), `permissions` (mode, allow, deny, bash rules), `sessions`
(retentionDays, compressDays), `mode` (plan by default, or build), `skills` (enabled),
`mcp` (servers), `httpClient` (headers, version, connectTimeoutSeconds, disableTlsCheck,
debug, proxy host/port/user/password), `structuralCodeGraph` (enabled, enrich, storeDir), `agents`
(name, prompt - inline text or a file path). The
`workingDir` option (default `.`) is the directory from
which project instructions and skills are discovered. The `projectDir` option
(defaults to `workingDir`) is the target project directory a task is about -
where generated/edited files belong. When `projectDir` points at a fresh or
empty directory (e.g. scaffolding a new project), the agent behaves like a
neutral scaffolder: it does not index an unrelated structural code graph and
does not inject an unrelated repository README/AGENTS.md, scoping to the
target directory the way a tool like opencode does. UI colors (under `tui`,
e.g. `accent`, `warning`, `error`, `success`) are themeable hex values;
markdown bold and inline-code colors are themeable as well (`strongColor`,
`inlineCodeColor`, each `auto` or a hex).

Context usage is estimated locally against `provider.contextWindow` and,
before each turn, the conversation is pruned at `pruning.thresholdPercent`
(default 65%) and compacted at `compaction.thresholdPercent` (default 80%).
If, despite this, the model provider rejects the request as exceeding its
real context window, the agent (when `llm.retryOnContextOverflow`, default
true) compacts the context once, reports "context window exceeded; compacting
and retrying", and retries the turn before falling back to an error.

Each MCP server entry accepts a `readonly` list of tool names that stay
available (read-only) in `plan` mode; `"*"` makes every tool of that server
read-only. `bash` commands are matched against `permissions.bash.rules` in
both modes: the defaults silently allow read-only commands (`git status`,
`git diff`, `ls`, `cat`, `grep`, `sed -n`, `java -version`...) and prompt for
the rest. Each rule targets a command start (`matcher.commandStart.options`,
token-wise so `git diff` does not match `git difftool`), optional
`matcher.excludeOptions` carve out write forms (`sed` is allowed, `sed -i` is
not), and per mode `plan`/`default` values (`ask`, `auto_edit` or `yolo`/`all`)
let a command behave differently in each mode - `default` inherits `plan` when
omitted. In `plan` mode the `READ`/`AGENT` tools, these read-only MCP tools
and the plan-resolved bash rules run without prompting; everything else
follows the `ask`/`auto_edit`/`yolo` permission mode (denied without an
interactive gate). `build` mode (full access, respecting the `permissions`
allow/deny lists and the default-resolved bash rules) is opt-in: switch to it
with `/mode build`, set `yca.mode=build`, or pass `--yca-run-mode build` for
`run`. Note that `/mode build` from `plan` also defaults the session approval
mode to `auto_edit`, while the headless `--yca-run-mode build` startup path
keeps its configured permission mode (no session switch is involved): a
headless `run` in `build` with `ask` still requires
`yca.permissions.mode=auto_edit/yolo` (or the TUI) for state-changing tools.

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
  (`index.bin` + optional `nodes/*.md`) and is regenerable, so it is git-ignored.

Because the per-project `.yca` directory concentrates regenerable caches (code
graph, project memory, the Maven dependency list), add it to your global
gitignore rather than adding it to every repository. Create or edit
`~/.config/git/ignore` (or per-platform `~/.gitignore_global`) and add:

```
.yca/
```

then register the file with `git config --global core.excludesfile ~/.config/git/ignore`.
This ignores `.yca` in every repo so caches and project state are never
accidentally committed.
- **Tools**: `code_resolve` is the single structural lookup tool. `op=resolve`
  returns the relations of a symbol (implementors/extensions, callers, callees,
  usages) with file:line locations from the local code graph, and notes which
  indexed Maven artifact provides a class; `op=list` enumerates the indexed
  artifacts. On demand, `maven_knowledge_content` reads a specific artifact's
  index. All are read-only and therefore available in `plan` mode too.

Configuration (`yca.structural-code-graph.*`): `enabled` (default `true`),
`enrich` (default `false`), `baseUrl`/`apiKey`/`model` (optional cheaper model
override for enrichment, falling back to the main provider), `maxFileBytes`
(default 5242880), `languageExtensions` (default `["java"]`), `storeDir`
(default `.yca/generated/graph`). Setting `enabled=false` disables the index
entirely.

Storage lives under `~/.yupiik/coding-agent`: one folder per session
(`sessions/<id>/` holding `<id>.jsonl`, `todos.json`, `plan.md` and
`snapshot.snap`), plus `memory/`, the prompt `history` and `configuration.json`
at the root; project memory is `.yca/memory/MEMORY.md`. Legacy flat layouts
(`sessions/<id>.jsonl`, root `todos-<key>.json`, `plans/*.plan.md`,
`snapshots/<id>.snap`) stay readable as a read-only fallback - nothing is
migrated or moved; root-level session artifacts orphaned by a deleted session
are swept at startup. At startup sessions inactive longer than
`sessions.retentionDays` (default 30) are dropped - the whole session folder
goes with them - and those inactive longer than `sessions.compressDays`
(default 15) are gzip compressed in place (`<id>.jsonl.gz`); a compressed
session is decompressed back to JSONL on resume (the `.jsonl.gz` is then
removed). Set a value to `0` to disable the corresponding cleanup.

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
  counts, session LLM call count and a context gauge; the panel also shows the
  permission mode (`ask`, `auto_edit`, `yolo`), with `yolo` rendered as a red
  `yolo ⚠` badge in `yca.tui.yolo` (default `#ff5555`)
- Provider throttling, retry backoffs and concurrency-limit waits surface as
  status-bar messages in the trailing slot (ⓘ, a few seconds) instead of
  blocking silently: rate limits and backoffs are labelled, and a blocked
  request reports `concurrency limit reached, N requests waiting` in the
  message. This only happens when a limit is configured -
  `yca.llm.rateLimitRequestsPerMinute` / `yca.llm.maxConcurrent` (or the
  per-provider `maxConcurrent`) - with the defaults (throttle off, unlimited
  concurrency) there is nothing to report
- Transcript and prompt have a mode-colored left bar (thin orange in `plan`,
  heavy bold purple in `build`); transcript renders as
  markdown with long lines wrapped at the panel width. Bold (`**strong**`)
  prose is a colored bold following `yca.tui.strongColor` (default `auto`,
  resolving to the primary `#fab283`) and inline `code` spans follow
  `yca.tui.inlineCodeColor` (default `auto`, resolving to the active code
  theme) on a subtle panel chip, so emphasis and inline code stay visible on
  both the dark and the light theme; inside dimmed regions (thinking, tool
  output) bold keeps the region tone (bold but dimmed) while inline code and
  links keep their own colors. Thinking streams inline
  in the same conversation panel, dimmed (text blended over the panel
  background at `yca.tui.thinkingOpacity`, default `0.6`) and marked with a
  thin dim left bar, and is always written into the transcript before each
  tool call and the final answer; regular output lines get a bold accent left
  bar so the main thread of the conversation stands out. Tool actions and
  their output are drawn the same subdued way (`yca.tui.toolOutputOpacity`,
  default `0.35`) so assistant answers stand out. Terminal colors have no
  alpha channel, so both opacities express how much the color is faded toward
  the panel background: 0 keeps the text color, 1 would hide it entirely
- Every speaker turn opens with a labeled header: a blank separator row, then a
  role-colored mark and label (`▌ You` in the primary color, `▌ Assistant` in
  the accent color) followed by a rule running out to the panel edge, so the
  boundary between a question and an answer stays readable while scrolling back
  instead of dissolving into the prose. Under `NO_COLOR` (mono palette) the
  colors collapse to the terminal default, so the mark switches to a distinct
  shape per role (`▌` user, `▐` assistant; `>`/`<` with `tui.glyphs=ascii`) and
  the rule becomes a plain line. The leading welcome block carries no marker and
  is left unlabeled.
- `Ctrl+T` toggles all thinking blocks (live stream + transcript history):
  hides every block when shown, shows them all back when hidden - nothing is
  lost, blocks stay in memory (same as `/thinking show|hide`, kept in sync)
- `Ctrl+Y` cycles executed tool blocks through three states: `shown` (full
  cards with args, output and the live stream), `collapsed` (a single dim line
  per tool - name and duration, failures marked - hiding args, output and the
  live stream) and `hidden` (nothing rendered for tools at all). The same
  states are available via `/tool-output show|collapse|hide`, kept in sync.
- Paste works through the terminal (ctrl+shift+v, middle-click, ...):
  bracketed paste is enabled so multiline content lands as-is in the prompt
  (newlines preserved, CRLF normalized) instead of being split into multiple
  submits; the prompt grows with its content up to a few rows
- The status bar shows per-turn metrics when the turn completes - LLM calls,
  thinking time, tool count and durations, turn duration - honoring
  `yca.metrics.displayMode` (compact/full/hidden, runtime-tunable with
  `/metrics compact|full`), plus session totals (`session: N LLM calls`) when
  `yca.metrics.showSessionTotals` is on (`/metrics session on|off`); `/metrics`
  alone prints the current configuration and the last turn values, and in `full`
  mode additionally breaks output down into visible vs. reasoning tokens
  (`out X tok · reasoning Y tok`, a reasoning-specific throughput) plus the
  session reasoning total when the provider reports one (`reasoning_tokens`)
- The transcript is capped in memory at
  `yca.tui.transcript.maxHistoryCharacters` (default `10000000`): once
  exceeded, the oldest content is truncated from the beginning
- `Ctrl+P` cycles the todos panel: open -> collapsed -> hidden; the panel
  opens automatically on the first todo update
- Switching `/mode build` keeps the plan todos recorded in `plan` mode, or
  derives them from the last plan of the session when the list is empty
- `Ctrl+S` compact (or scheduled at the next agent checkpoint while a turn is running), `Ctrl+L` clear, `Ctrl+C` quit, `Esc` interrupt (also drops queued and scheduled prompts); `Shift+Tab`
  flips between `plan` and `build` modes (same as `/mode`), `Tab` completes
  slash commands and their parameters
- Mouse drag selects any visible text (transcript, sidebar, status bar...) and
  copies it to the system clipboard on release; double/triple-click select a
  word / a whole row, `Ctrl+Shift+C` re-copies the selection and `Esc` clears
  it. Copying uses OSC 52 so it requires a terminal supporting it (kitty,
  alacritty, iTerm2, Windows Terminal, tmux with clipboard passthrough, ...)
- The mouse wheel scrolls the transcript and every modal or overlay (approval,
  `btw`, `/model`, `/tabs`, shortcuts, subagents, search, full tool output,
  last error); the amount is configurable with `yca.tui.scrollLines` (per notch,
  default 3) and `yca.tui.scrollPageLines` (per `PgUp`/`PgDn`, default 10)
- Each tab shows its own mode: a per-tab badge (▪ plan / ◆ build) in the tab
  bar tinted with that session's mode color (warning for plan, accent for
  build) - background tabs included, so the mode never depends on which tab is
  active. Moving the mouse over a tab shows the full session title as a small
  floating tooltip under the strip (truncated titles stay reachable). The
  `/tabs` overlay lists the badge + mode + full title too.
- While a tool runs, a line under the input shows `calling <command>` with a
  spinner (and `esc to interrupt` on the right) and its output is streamed live
  into the transcript
- Waiting states get per-phase spinners: clock for first-token/agent waits
  (`thinking... (Ns)`, `busy - press Esc to interrupt`, `interrupting...`), square
  corners for streaming/calling a tool (`calling <command>`, `running <command>`),
  dots for generic agent work (`agent is working...`) and a bouncing ball for
  other background operations (`compacting (chunk i/n)...`, resyncing the
  structural code graph); the status bar shows the same animated feedback and the
  TUI/CLI share the same label format (`BusyLabels`)
- While a running tool's output overlay is open, `Esc` cancels that tool only
  (the turn keeps running and the next round sees the cancelled result);
  `Ctrl+O`/`q`/`Esc` on a finished card just closes the modal
- Code blocks in the transcript get basic syntax highlighting (java, ts, python,
  shell, sql, xml/json, plus a generic fallback)
- Slash commands: `/help`, `/shortcuts`, `/new`, `/resume`, `/compact [force]` (during a turn the compaction is scheduled and applied at the next agent checkpoint, at most once per turn), `/model [$name]` (list, or set the model - `Tab` completes the name; per-tab in the TUI, per-session in the CLI), `/provider [id]` (show or switch the provider - also per-tab: the choice lands in the session's settings, not the shared process default; `default`/blank restores the process default provider, resetting the model/variant/sampling selection since they are provider-scoped), `/mode build|plan` (during a turn the switch is queued and applied when the current turn ends, keeping the running turn's mode intact; switching back to `plan` resets the session approval mode to the configured default and clears the in-memory session grants; entering `build` from `plan` defaults the session approval mode to `auto_edit`), `/agent [name]` (list custom agents, or set the active agent - `Tab` completes the name), `/memory`, `/context [transcript|llm-payload|next-request|compact|prune] [path]` (`transcript` (default) shows the persisted session, `llm-payload` the exact last request sent to the LLM - full message contents and tool schemas, `next-request` the request the next round would carry, rebuilt from the persisted session; `compact|prune` trigger an idempotent context operation; write to path when given), `/permissions ask|auto_edit|yolo|none|show` (switch the approval mode interactively; `none` restores interactive approval, `show` lists the current mode and in-memory session grants - approvals granted in `build` are cleared when switching back to `plan`), `/tools [filters]` (list tools per MCP server; space separated filters must all match the lowercase server/tool name), `/clear`, `/quit`
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
