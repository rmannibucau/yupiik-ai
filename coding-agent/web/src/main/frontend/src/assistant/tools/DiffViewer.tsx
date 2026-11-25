// Adapted from assistant-ui Elements DiffViewerLine (MIT).
// See LICENSE.assistant-ui here and documentation/src/main/minisite/content/features/ui-advanced.adoc.
import { lazy, Suspense, type HTMLAttributes } from 'react';
import { canRenderDiff } from '../../lib/fileChanges';

export type DiffMode = 'auto' | 'side-by-side' | 'simple';
const SplitDiff = lazy(() => import('./SplitDiff'));

type DiffViewerProps = HTMLAttributes<HTMLElement> & {
  text: string;
  startsAtLine?: boolean;
  mode?: DiffMode;
  path?: string;
  oldSource?: string;
};

/** App-owned boundary. Auto follows tool disclosure and uses split layout when a complete patch is available. */
export function DiffViewer({ mode = 'simple', path, oldSource, ...props }: DiffViewerProps) {
  const simple = <SimpleDiff {...props} />;
  const complete = props.startsAtLine !== false && canRenderDiff(props.text)
    && /^(?:diff --git [^\n]+\r?\n|--- [^\n]+\r?\n\+\+\+ )/.test(props.text);
  if (mode === 'simple' || !complete) {
    return simple;
  }
  return <Suspense fallback={simple}><SplitDiff {...props} path={path} oldSource={oldSource} fallback={simple} /></Suspense>;
}

/** Literal patch lines, not a computed or reconstructed diff.
 * The caller supplies a bounded window and whether its first line is complete.
 * Unlike the upstream parser, this preserves CRs, markers and final newlines.
 */
function SimpleDiff({ text, startsAtLine = true, ...props }: DiffViewerProps) {
  return <pre {...props} data-slot="diff-viewer"><code>{text.split('\n').map((line, index, lines) => {
    const type = index === 0 && !startsAtLine ? 'normal'
      : line.startsWith('+') ? 'add' : line.startsWith('-') ? 'del' : line.startsWith('@@') ? 'marker' : 'normal';
    const className = type === 'add' ? 'yca-diff-add' : type === 'del' ? 'yca-diff-remove' : type === 'marker' ? 'yca-diff-hunk' : undefined;
    // Preserve the upstream separate indicator/content slots, but never invent
    // an indicator for context, markers or a continuation in a paged line.
    const indicator = type === 'add' || type === 'del' ? line[0] : '';
    const content = indicator ? line.slice(1) : line;
    return <span key={index} data-slot="diff-viewer-line" data-type={type} className={className}>
      <span data-slot="diff-viewer-indicator">{indicator}</span><span data-slot="diff-viewer-content">{content}{index < lines.length - 1 ? '\n' : ''}</span>
    </span>;
  })}</code></pre>;
}
