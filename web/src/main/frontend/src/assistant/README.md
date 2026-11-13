# YCA assistant-ui integration

YCA uses the locked `@assistant-ui/react` 0.15.28, `@assistant-ui/react-markdown` 0.14.21, and `@assistant-ui/react-syntax-highlighter` 0.14.7 packages.
Source-installed Elements are pinned to an upstream revision, not fetched dynamically at runtime.
Their original MIT notices, source URLs, hashes/revision and adaptation notes live in `elements/UPSTREAM.md`, `actions/UPSTREAM.md`, and `tools/PROVENANCE.md`.
`Reasoning.tsx` adapts the same pinned Elements reasoning source with native disclosure controls and the installed `useScrollLock` hook.
No assistant cloud, browser MCP, AI SDK transport, new provider, or new runtime dependency is introduced.

## Ownership boundary

- `YcaRuntime` adapts the existing SSE event log into `useExternalStoreRuntime`.
  Its messages are presentation projections only; they must never be persisted, sent to a provider, compacted, or used for provider cache identity.
- `runtime/eventsToMessages` caches unchanged message/part/metadata references and preserves origin identities across live updates, repeated prompts, prepended history and reconstructed replay.
  Backend DTOs lack persisted occurrence IDs, so a wholly reconstructed sequence of indistinguishable identical turns cannot distinguish append from prepend; see the projector comments/tests.
- `ThreadPrimitive.Root`, `Viewport`, ID-keyed message contexts, `MessagePrimitive.Root` and `Parts` own generic runtime composition.
  The viewport's automatic scrolling is disabled deliberately: YCA's strict Follow/manual-pause behavior and explicit history anchoring remain the only scroll policy.
- `ComposerPrimitive.Root`, `Input`, and `TriggerPopover` provide composer and accessible suggestion primitives.
  The Java backend's `slash.complete` owns replacements, arguments and command catalogs, so YCA uses a backend-driven trigger adapter instead of the static execute-on-selection slash adapter.
  Selecting a completion never dispatches a command, adds a trailing space, or discards its suffix.
- Prompt queueing remains server-owned.
  The Send button deliberately stays available during a turn and calls the existing RPC queue contract; it does not create a competing assistant-ui queue.
  Stop and Resume retain their distinct backend meanings.
- Editing, regeneration, branches, attachments, voice, provider feedback and browser MCP are not exposed because the backend does not implement those contracts.

## Retained YCA-specific code

`ChatPage` still coordinates session lifecycle, metadata, usage/quota metrics, slash command modals (`/help`, `/btw`, `/executing-tools`, `/subagents`, tabs), resize settings and exact Follow semantics.
These are not replaced with unrelated generic capabilities.
Markdown uses assistant-ui's Markdown primitive/highlighter while retaining YCA callouts, reference scrubbing, grammar aliases and bounded streaming normalization.
Tool renderers keep exact raw arguments/output, bounded paging, conservative unknown-tool fallback, requested-versus-applied distinctions, full-file previews and pending/interrupted/failed states.
The copied ToolCall/DiffViewer Elements supply reusable presentation, not tool execution.
Tools and Thoughts share an accessible Hidden/Collapsed/Expanded toolbar menu, with Collapsed as the default.
Thought disclosure remains a web presentation setting; both visible modes retain the backend `/thinking show` contract, and Hidden uses `/thinking hide`.
Per-card manual disclosure survives hide/show, and streamed reasoning does not force open a globally collapsed card.
Collapsed sidebar todos keep the current task and progress count on the disclosure row, with truncation and the full task available as a tooltip.

Questions/approvals use the copied OptionList, ElicitationForm and ApprovalCard Elements.
Session requests retain FIFO ordering, duplicate-submit protection and retryable errors/drafts.
`session.ask` returns ID-keyed feedback answers; `session.question` preserves scalar readLine, picker and boolean-confirm contracts.
Source SSE session IDs are carried through the dispatcher so late frames cannot be answered against a different tab.
See `actions/README.md` for exact wire tokens and lifecycle details.

Todos, MCP, memory and session search use copied Elements with backend adapters.
Missing MCP metadata, memory facts, agent progress and per-message search matches are never invented.
Full raw memory files remain visible; session content search remains server-side and project-grouped.

## Checks

From this directory's frontend root:

```sh
npm test
npx tsc -p tsconfig.check.json
npm run build
```

From the repository root, rebuild the complete Java application before end-to-end validation:

```sh
mvn spotless:apply clean install -T1C
java --enable-native-access=ALL-UNNAMED -jar yca/target/yca-bin-1.0.0-SNAPSHOT-fatjar.jar web --port 16060 --openBrowser false
node web/src/test/browser/assistant-ui.mjs http://127.0.0.1:16060 --all
```

The browser runner needs installed Chrome and a Node version with native WebSocket support, but no extra npm dependency.
It uses an isolated Chrome profile, records screenshots/console/network exceptions under ignored `web/target/browser-validation`, and cleans up disposable real sessions and Chrome.
Real checks use the implicit configured model and a short read-only turn.
Fixture checks load the actual built app but replace only API/SSE inputs to exercise deterministic large-history, tool-state, question and approval edges without unsafe operations.
Reports distinguish real-provider and fixture checks; a fixture pass is not a claim that a real provider emitted every edge case.
Documentation minisite and native-image builds are unnecessary for Java-mode validation.

## Performance evidence

Run `node web/src/test/browser/projection-benchmark.mjs` from the repository root to compare HEAD's reducer with the current reducer/projector on identical synthetic input.
The script asserts block equivalence and reference reuse and writes samples/source hashes under ignored `web/target/browser-validation/projection-benchmark.json`.
It measures Node computation, not browser frame latency or the full runtime pipeline.
The 5,000-turn sample measured a 29.60 ms projector snapshot versus 4.69 ms for the bare HEAD reducer, and an 18.77 ms median live projection versus 4.65 ms for that reducer.
Stable IDs and reference caching add computation; this migration is not claimed to make reduction faster.
Compact IDs, exact-origin interning and immutable-event caches reduced the initial migration's roughly 62/90 ms snapshot/live measurements without hashing collisions or changing backend history.
All eligible unchanged references were retained in the synthetic live scenario.
Chrome checks separately verify real rendering/interaction, bounded tool paging, stable replay nodes and pixel-stable history anchoring.
