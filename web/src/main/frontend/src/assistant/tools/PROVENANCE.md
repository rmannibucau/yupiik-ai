# Rendering integration provenance

Canonical source fetched from [assistant-ui/assistant-ui](https://github.com/assistant-ui/assistant-ui/tree/f89ae49df57862c3645493bce84aabb2b111bda0), revision `f89ae49df57862c3645493bce84aabb2b111bda0` (MIT; license retained alongside these files).

- [ToolCall Element](https://github.com/assistant-ui/assistant-ui/blob/f89ae49df57862c3645493bce84aabb2b111bda0/packages/ui/src/components/react/assistant-ui/elements/tool-call.tsx): controlled disclosure, label/query trigger and separate request/result composition adapted in `ToolCall.tsx`.
- [ToolFallback Element](https://github.com/assistant-ui/assistant-ui/blob/f89ae49df57862c3645493bce84aabb2b111bda0/packages/ui/src/components/react/assistant-ui/elements/tool-fallback.aui.tsx): root scroll anchoring uses the actual exported `useScrollLock` hook, with the upstream 200ms lifetime and cleanup.
- [DiffViewer Element](https://github.com/assistant-ui/assistant-ui/blob/f89ae49df57862c3645493bce84aabb2b111bda0/packages/ui/src/components/react/ui/base/diff-viewer.tsx): line type styling and data-slot composition adapted in `DiffViewer.tsx`.

## Deliberate adaptations

ToolCall uses native button/panel semantics instead of Base UI Collapsible, ReactNode request/result slots instead of unbounded string paragraphs, and existing YCA classes instead of upstream Tailwind surfaces/icons/animations.
It lazily mounts details, retains the standalone controlled API, and displays YCA's working/pending/interrupted/failed/completed distinctions instead of treating all settled calls as successful.
ToolDisclosure is a local lazy native details control for raw payloads, not an upstream component.

DiffViewer renders literal supplied lines inside pre/code rather than parsing/reconstructing a patch or computing a diff from old/new files.
It retains the original line indicators, CRs, blank lines and terminal newline without synthetic line numbers or context.
The caller pages characters before rendering and tells it when the first visible line is partial.
The existing toolDetails parser and toolPresentation/toolOutput decisions remain authoritative; requested patches never become claims of applied changes and write_file never becomes a fabricated diff.

## Installed APIs checked

The integration uses `@assistant-ui/react` 0.15.28 (`TextMessagePartProvider`, `useScrollLock`), `@assistant-ui/react-markdown` 0.14.21 (`MarkdownTextPrimitive` and its SyntaxHighlighter override), and `@assistant-ui/react-syntax-highlighter` 0.14.7 (`makePrismLightSyntaxHighlighter`).
Installed `src/primitives/MarkdownText.tsx`, `src/overrides/CodeOverride.tsx`, `src/overrides/CodeBlock.tsx`, `src/make-syntax-highlighter.tsx` and `src/react-syntax-highlighter-light.ts` were checked, rather than assuming the latest documentation's API.
CodeBlock keeps the existing synchronous PrismLight grammars from the installed react-syntax-highlighter peer and delegates rendering through the assistant-ui factory.
MarkdownContent provides text locally with smoothing disabled, so documents and replay snippets do not require a chat runtime or lose code whitespace.

## Dependencies and CSS

No new dependencies or global CSS changes are needed.
Existing react-syntax-highlighter, remark-gfm and the assistant-ui packages remain required; direct react-markdown imports are no longer needed by these components, but it remains an upstream dependency.
Existing `.aui-md`, `.yca-code-block`, `.token`, `.yca-tool-*` and `.yca-diff-*` styling is retained.
No upstream Base UI, lucide-react, diff, parse-diff, class-variance-authority, Tailwind surface tokens or animation CSS is required by these adaptations.
