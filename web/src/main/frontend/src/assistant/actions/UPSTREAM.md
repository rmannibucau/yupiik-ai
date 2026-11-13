# Upstream Elements provenance

Fetched the current public assistant-ui Elements registry on 2026-11-12/13.
The registry's `files[].content` is the actual reusable component source, not example markup.
The matching upstream project is <https://github.com/assistant-ui/assistant-ui>.
Upstream uses the MIT license, reproduced in `LICENSE.assistant-ui` (copyright 2026 AgentbaseAI Inc.).
`upstream-manifest.json` records the registry URLs, canonical repository source paths, and SHA-256 hashes of each original source string before adaptation.
Registry URLs are mutable; the recorded hashes identify the source used for this migration.

## Source reused

| Local file | Canonical registry |
| --- | --- |
| `elements/approval-card.tsx` | <https://r.assistant-ui.com/elements-approval-card.json> |
| `elements/option-list.tsx` | <https://r.assistant-ui.com/elements-option-list.json> |
| `elements/elicitation-form.tsx` | <https://r.assistant-ui.com/elements-elicitation-form.json> |
| `elements/receipt-focus.ts` and `elements/useIsomorphicLayoutEffect.ts` | <https://r.assistant-ui.com/elements-receipt-focus.json> |
| `elements/range.ts` | <https://r.assistant-ui.com/elements-range.json> |

Also inspected the canonical [PermissionGrant](https://r.assistant-ui.com/elements-permission-grant.json), [QuestionFlow](https://r.assistant-ui.com/elements-question-flow.json), and [Surfaces](https://r.assistant-ui.com/elements-surfaces.json) source.
PermissionGrant offers a global `always` grant, which YCA does not support; ApprovalCard's label override accurately expresses the session-scoped permission instead.
QuestionFlow commits steps individually and has no own-answer field, so grouped YCA questions compose the real OptionList with a staged-selection extension rather than pretending the backend is an upstream flow runtime.
Free-text readLine requests use the real ElicitationForm.

## Local adaptations

- Changed module aliases to relative imports.
- Replaced lucide-react imports with small local decorative SVG adapters in `elements/icons.tsx`, avoiding an additional package for five icons.
- Replaced the shared Surfaces dependency with a minimal `cn`/class-recipe adapter using already-installed `clsx` and `tailwind-merge`.
  No shimmer or animation package is needed by these interactions.
- Added scoped `actions.css` to map upstream semantic palette utilities to YCA's existing CSS variables without editing global CSS.
- Added braces to upstream brace-less control flow to match project rules.
- Extended OptionList with controlled `value`, `onValueChange`, and `disabled` props.
  This preserves upstream option rows, selection presentation, receipts, async error behavior, and standalone confirm behavior while supporting a single atomic Submit across grouped questions.
  Controlled single choices expose `aria-pressed`; controlled multiple choices retain upstream `role="checkbox"` and `aria-checked` semantics.
  Locked options use native disabled in addition to upstream ARIA state.
- Extended ElicitationForm with `acceptLabel` and `declineLabel` props (upstream defaults unchanged) for YCA's Submit/Cancel terminology.
- ApprovalCard, ElicitationForm, OptionList, and the receipt-focus/layout hooks remain upstream-derived reusable components, separately composed by the YCA-specific SessionRequests adapter.

`SessionRequests.tsx`, `useInteractiveRequests.ts`, the theme/icon adapters, scoped CSS, and the mounted tests are local integration code.
