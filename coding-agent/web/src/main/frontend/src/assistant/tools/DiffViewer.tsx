// Adapted from assistant-ui Elements DiffViewerLine (MIT).
// See LICENSE.assistant-ui here and documentation/src/main/minisite/content/features/ui-advanced.adoc.
import type { ComponentPropsWithoutRef } from 'react';

/** Literal patch lines, not a computed or reconstructed diff.
 * The caller supplies a bounded window and whether its first line is complete.
 * Unlike the upstream parser, this preserves CRs, markers and final newlines.
 */
export function DiffViewer({ text, startsAtLine = true, ...props }: ComponentPropsWithoutRef<'pre'> & {
  text: string;
  startsAtLine?: boolean;
}) {
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
