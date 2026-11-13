# assistant-ui Elements source adaptation

The adjacent `todo-list.tsx`, `subagent-list.tsx`, `agent-card.tsx`, `agent-status.tsx`, `mcp-server-panel.tsx`, `memory-chips.tsx`, and `thread-search.tsx` are source copies adapted from:

- Repository: https://github.com/assistant-ui/assistant-ui
- Pinned revision: `f89ae49df57862c3645493bce84aabb2b111bda0`
- Source directory: `packages/ui/src/components/react/assistant-ui/elements/`
- Registry inspected: `apps/registry/src/registry.ts` (matching slugs, e.g. `todo-list`, `thread-search`, `mcp-server-panel`).
- License: MIT, Copyright (c) 2026 AgentbaseAI Inc.; full notice in `LICENSE.assistant-ui`.
- `range.ts` retains only `clamp` and `pct` from `packages/ui/src/components/react/assistant-ui/utils/range.ts`.
- `surfaces.ts` retains only used recipes from upstream `surfaces.tsx`, mapped to YCA CSS variables.

Fetched actual sources and registry at the pinned revision, rather than recreating the components from screenshots or documentation.

## Local changes

- Imports use existing `clsx` / `tailwind-merge` and local, presentation-only SVG icons (`icons.tsx`) instead of `@/lib/utils` and `lucide-react`.
- Color utilities map to existing `--yca-*` variables; layout, props-driven behavior, accessibility slots, and composition remain upstream-derived. No global stylesheet changes.
- Control-flow statements use braces.
- ThreadSearch adds `serverFiltered`, `loading`, `searchLabel`, `emptyLabel`, and `showGroupCounts`. YCA backend search includes session content, so supporting pages must **not** re-filter server results against only titles/previews. Project group order and within-group recency are supplied by SessionsPage. Query changes debounce for 200ms; stale requests are ignored.
- McpServerPanel accepts optional tools/transport metadata, a backend-redacted endpoint URL, `unknown` status, an exact `stateLabel`, and optional `onReset(id)` / `resetting`. Missing metadata is not displayed as a fabricated zero tool count. YCA ENABLED/RUNNING maps to connected, DISABLED to disabled, PENDING to connecting, and FAILED to failed; other states retain their exact label with a neutral indicator. Both the MCP page and session sidebar support expandable connection details. Reset uses the existing backend API, not authorize/connect semantics.
- MemoryChips adds an optional `label`. MemoryPage uses chips only for explicit file existence metadata, **never** to infer facts from unstructured text. Both raw files, full paths, empty/missing placeholders, and RPC errors remain visible.
- `subagent-activity.tsx` is a YCA adapter using the copied AgentStatus for independently ordered concurrent states. It deliberately does not invent progress percentages. The original SubagentList (completed-prefix/progress-driven), AgentCard (connection metadata), and AgentStatus remain available separately.

## Integration exports

Import from `src/assistant/elements`:

- `TodoList`, `TodoItem`, `TodoStatus`: upstream statuses `pending | active | done | failed | cancelled`; optional `revision`, `maxVisible`, `title`, `description`. Folding always keeps active/failed items visible.
- `SubagentActivity`, `SubagentActivityItem`: `{ id, name, state, label, model?, elapsed?, description? }[]`; states `working | waiting | done | failed`. Parent owns backend event mapping.
- `McpServerPanel`, `McpServer`, `McpServerStatus`: controlled `servers`, `expandedId`, `onToggle`, optional `onAuthorize` and `onReset`; no RPC calls inside the component.
- Also `SubagentList`, `AgentCard`, `AgentStatus`, `MemoryChips`, `ThreadSearch` and their exported data types.

ChatPage integrates TodoList, SubagentActivity and McpServerPanel; supporting pages integrate ThreadSearch, MemoryChips and McpServerPanel. These Elements require no additional dependencies or global CSS. Only existing React, clsx and tailwind-merge are imported.
