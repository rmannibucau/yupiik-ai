# Session questions and approvals

Self-contained migration of ChatPage's RequestModal and useInteractiveRequests.
All implementation, test, and provenance files are under this directory.
No page, package manifest, lockfile, backend, or index.css changes are required by this implementation.

## Integration

ChatPage integrates the component and hook as follows:

```tsx
import { SessionRequests, useInteractiveRequests } from '../assistant/actions';

// Replace the page-local hook, do not mount both subscriptions.
const { pending, answer, pendingCount } = useInteractiveRequests(sessionId);

// Replace RequestModal. No callback cast is needed.
<SessionRequests request={pending} onAnswer={answer} pendingCount={pendingCount} />
```

Remove the old page-local hook and modal definitions to avoid duplicate names and subscriptions.
`SessionRequests` is controlled by `request` and accepts `onAnswer(id, value): void | Promise<void>`.
It therefore also works with the old `pending`/`answer` shape, but a callback that returns void cannot report a failed asynchronous POST.
Use the exported hook for reliable error reporting and queue behavior.
The page must keep its existing useSessionStream SSE integration, which calls dispatchServerRequest.
The component imports `actions.css` itself; no global stylesheet changes or assistant-ui runtime provider are needed.

### Dependencies

Only React, clsx, and tailwind-merge, all already installed.
Tests use existing react-dom, vitest, and happy-dom.
No lucide-react, shadcn, Radix, shimmer, or other dependency is added.
These Elements are props-driven source components, not MessagePrimitive approval components requiring a tool-call runtime.
See UPSTREAM.md, LICENSE.assistant-ui, and upstream-manifest.json for actual reusable upstream source provenance and adaptations.

## Exact answer contracts

The hook POSTs `session.resolve` with `{ id: capturedSessionId, requestId: String(request.id), result: value }`.
It consumes the backend's `{ resolved: boolean }` result, not a bare boolean.

| Interaction | `result` |
| --- | --- |
| Approval: Allow once | `"allow_once"` |
| Approval: Allow session (upstream always-allow callback) | `"allow_session"` |
| Approval: Deny or Escape | `"deny"` |
| `session.ask` questions (one or many) | object keyed by original question IDs; single-choice values are selected strings, trimmed own text, or `""`; multiple-choice values are arrays in original option order, with selected nonblank trimmed own text last |
| `session.question`, one single-choice question | selected option string, or trimmed own text, or `""` |
| `session.question`, one multiple-choice question | array in original option order, with selected nonblank trimmed own text last |
| `session.question`, multiple questions | object keyed by original question IDs; values follow the single/multiple-choice rules above |
| Free-text readLine (`session.question`) | trimmed string, including `""` |
| Picker | exact selected label, including an empty string |
| Prompt-only confirm | `true` or `false` |
| Any question/picker/confirm cancel or Escape | `null` |

Approval does not offer a global permission: `allow_session` is the backend's maximum scope.
The detail and serialized tool arguments are visible without the old 600-character truncation.
Own text is included only when selected, except optionless single-choice questions, preserving RequestModal's existing behavior.
Questions are tracked by array index and options by local index, avoiding string-prefix collisions; only original IDs and label values are sent.

WebShellIO.feedbackGate sends `session.ask` and requires an ID-keyed map for every question count, including a single optionless question.
The askRpc readLine/pick/confirm path sends `session.question`; readLine requires the scalar string contract.
The existing method distinction determines the question payload without backend changes.
Picker and prompt-only confirmation payloads are independent of the request method.
Prompt-only confirm is now supported correctly (the old modal submitted an empty object instead of a boolean).

## Async, queues, and session boundaries

- FIFO per-session queues prevent newer requests from overwriting unanswered ones.
- Pending and completed request IDs are deduplicated for the hook's lifetime.
- Each resolution captures the request's owning session; a delayed completion never clears another session's pending request.
- Navigating between sessions preserves unanswered queues while this hook remains mounted.
- In-flight answers are coalesced by session/request ID and the component synchronously locks against double-clicks.
- Transport/JSON-RPC errors and malformed acknowledgements retain the pending request and draft, display an alert, and permit retry.
- `{ resolved: false }` means the server future no longer exists (Stop, timeout, another client); the hook retires that request, advances the queue, and rejects the caller's promise with an explanation.
  A retired request's dialog unmounts, so that particular explanation is not retained as a global toast.
- The component suppresses stale promise state updates after unmount or request/session replacement.
- Keyboard focus is initially placed inside the labelled modal, Tab/Shift+Tab cycle inside it, Escape explicitly cancels/denies, and prior focus is restored on close.
  Backdrop clicks do not silently abandon a request.

## Pending requests across navigation and reload

The Java backend retains each original RPC payload while its future is pending.
SSE bootstrap registers the subscription and replays only that session's outstanding requests after its snapshot.
Resolved, cancelled and expired requests are excluded.
Duplicates from concurrent publication/replay use the same session/request ID and are deduplicated by the hook.
The replay lifetime is the live backend session, not persisted model history; restarting the backend does not restore futures.


rpc.ts delivers requests only to the most recently registered handler.
useSessionStream now carries the source session ID through dispatchServerRequest/onServerRequest as optional request metadata.
The hook rejects frames whose source session does not match the active subscription, preventing late frames from another session's stream from being misattributed.
Older callers without source metadata remain scoped to the active subscription and cannot receive that mismatch protection.
Frames missed while there is no active page are replayed when that session next subscribes, provided the backend future is still pending.
Hook queues themselves are in-memory and reset on remount; server replay repopulates them.
Server cancellation has no dedicated request-retired event; a stale visible request is retired when `session.resolve` reports `resolved: false`.
Keep a single active interactive-request subscription.

## Verification

```bash
npm test -- src/assistant/actions
npx tsc -p tsconfig.check.json
```

Mounted happy-dom tests exercise the actual registry-derived elements, all wire payload shapes, picker and confirmation, cancel/Escape, focus behavior, asynchronous and synchronous failures, retries, rapid duplicate submissions, queue arrivals during an in-flight POST, deduplication, session switching, stale completion, expired futures, and subscription cleanup.
The hook tests use the real rpc.ts dispatcher and mock only fetch.
