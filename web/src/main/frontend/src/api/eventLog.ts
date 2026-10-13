import type { WebEvent } from './useSessionStream';

/**
 * The transcript blocks derived from the raw SSE event log. Pure data: the same events always
 * produce the same blocks, which is what makes the reducer unit-testable (temporary vitest) and
 * cheap to re-run per render batch (it is O(events), and events arrive rAF-batched).
 */
export type Block =
  | {
      kind: 'text' | 'thought' | 'error' | 'user';
      content: string;
      state?: string;
      output?: string;
      // true while a live stream is still appending to this block (text-delta/thinking-delta since the
      // last non-streaming event). Renderers debounce such blocks so a half-open markdown span (an
      // inline-code backtick whose closer is still streaming) is never parsed into a bogus pairing.
      writing?: boolean;
    }
  | {
      kind: 'tool';
      name: string;
      content: string;
      // 'running' while live, 'done' when a tool-finished resolved it, 'interrupted' when a turn
      // boundary (cancel/done/error) ended the turn without a tool-finished for this card.
      state: 'running' | 'done' | 'interrupted';
      // the raw tool-arguments JSON carried by tool-started (for bash the `command` / workdir,
      // for read_file the `path`, ...), used to guess the output language for highlighting.
      // Null when the event carried none (e.g. replayed history with empty args).
      args?: string;
      output?: string;
      success?: boolean;
      toolOrdinal?: number;
    };

const EMPTY_TOOL_STACK: number[] = [];

/**
 * Terminal tool-state marker used when a turn boundary (cancelled/done/error) leaves a tool card still
 * "running": no `tool-finished` is ever going to arrive for it (the turn already ended - e.g. a cancel
 * hit between the assistant tool_calls and the tool result), so the card is settled as interrupted
 * rather than left spinning "working" forever.
 */
export const INTERRUPTED = 'interrupted' as const;

/**
 * Reduces the ordered event log into transcript blocks. Appends never mutate earlier blocks
 * (text-delta only touches the LAST block), so a memoized BlockView keyed by block index sees
 * stable content for everything except the live tail.
 *
 * Running tools are tracked per name (a deque of block indices), mirroring the core
 * ConversationTranscript's runningTools: BOTH tool-stream and tool-finished bind to the most
 * recently started card with that tool name, so they can never "finish" the wrong card (which
 * previously left cards stuck in the 'running' state).
 */
export function eventsToBlocks(events: WebEvent[]): Block[] {
  const blocks: Block[] = [];
  const runningTools = new Map<string, number[]>();
  // Any event that is not a streaming delta marks the end of the current stream burst, so the trailing
  // text/thought block(s) are no longer "writing" and renderers may debounce-flush them.
  const settle = () => {
    for (let i = blocks.length - 1; i >= 0; i--) {
      const b = blocks[i];
      if (b.kind === 'text' || b.kind === 'thought') {
        b.writing = false;
        break;
      }
      if (b.kind === 'tool') break; // text/thought only trail after a tool card
    }
  };
  // A turn boundary means none of the still-running tool cards will ever get a tool-finished (a cancel
  // landed between the assistant tool_calls being persisted and the tool result). Settle them all as
  // 'interrupted' and clear the running stack so no card is left spinning "working" after the turn ends.
  const settleInterrupted = () => {
    for (const [, stack] of runningTools) {
      for (const idx of stack) {
        const b = blocks[idx];
        if (b?.kind === 'tool' && b.state === 'running') {
          b.state = INTERRUPTED;
        }
      }
    }
    runningTools.clear();
  };
  for (const ev of events) {
    const last = blocks[blocks.length - 1];
    if (ev.type === 'text-delta') {
      if (last?.kind === 'text') {
        last.content += ev.message ?? '';
        last.writing = true;
      } else {
        blocks.push({ kind: 'text', content: ev.message ?? '', writing: true });
      }
    } else if (ev.type === 'thinking-delta') {
      if (last?.kind === 'thought') {
        last.content += ev.message ?? '';
        last.writing = true;
      } else {
        blocks.push({ kind: 'thought', content: ev.message ?? '', writing: true });
      }
    } else if (ev.type === 'tool-started') {
      settle();
      const name = ev.message ?? ev.type;
      const idx = blocks.length;
      const ord = ev.data?.ordinal;
      const args = typeof ev.data?.args === 'string' && ev.data.args.trim() ? ev.data.args : undefined;
      blocks.push({
        kind: 'tool',
        name,
        content: '',
        state: 'running',
        args,
        toolOrdinal: typeof ord === 'number' && ord > 0 ? ord : undefined,
      });
      runningTools.set(name, [...(runningTools.get(name) ?? EMPTY_TOOL_STACK), idx]);
    } else if (ev.type === 'tool-stream') {
      settle();
      // the DTO maps message=name and data.fragment (the live stdout fragment). Bind by the per-call
      // ordinal when the runner stamped one (repeated same-name tools then never mis-route a late
      // stream), falling back to the most recent running card for legacy events without an ordinal.
      const toolBlock = ordinalBlock(blocks, runningTools, ev);
      if (toolBlock && toolBlock.kind === 'tool') {
        toolBlock.content += (ev.data?.fragment as string) ?? '';
        toolBlock.content += '\n';
      }
    } else if (ev.type === 'tool-finished') {
      settle();
      const stack = runningTools.get(ev.message ?? '');
      // prefer finishing the exact started call identified by the runner's ordinal; fall back to the
      // name deque (parallel batches, legacy events) when the ordinal never matched.
      let toolBlock: Block | undefined;
      if (stack && stack.length > 0) {
        const ord = ev.data?.ordinal;
        const idx = typeof ord === 'number' && ord > 0
          ? stack.findIndex((i) => blocks[i]?.toolOrdinal === ord)
          : -1;
        if (idx >= 0) {
          toolBlock = blocks[stack.splice(idx, 1)[0]];
        } else {
          toolBlock = blocks[stack.pop()!];
        }
        if (stack.length === 0) runningTools.delete(ev.message ?? '');
      }
      if (toolBlock && toolBlock.kind === 'tool') {
        toolBlock.state = 'done';
        toolBlock.output = (ev.data?.output as string) ?? '';
        toolBlock.success = ev.data?.success !== false;
      }
    } else if (ev.type === 'user') {
      settle();
      blocks.push({ kind: 'user', content: ev.message ?? '' });
    } else if (ev.type === 'btw') {
      // /btw answers live in the status bar pill + modal (folded from the same log by foldStatus),
      // never in the transcript stream - the side conversation must not pollute the main thread.
      // Deliberately no-op: the event carries no transcript block.
    } else if (ev.type === 'error') {
      settle();
      settleInterrupted();
      blocks.push({ kind: 'error', content: ev.message ?? '', state: 'done' });
    } else if (ev.type === 'turn-done') {
      settle();
      settleInterrupted();
      // The final assistant message is authoritative and complete: it must SUPERSEDE whatever
      // streamed into the tail, never be appended (appending duplicated the whole answer whenever
      // the final content differed from the accumulated stream by a trailing char or spanned two
      // reasoning rounds). Mirror the core ConversationTranscript consolidation: when the streamed
      // text is a fuller superset of the final content (a clipped provider reply) the streamed text
      // wins and nothing is lost; otherwise the final content replaces the tail (regeneration or a
      // genuine extension of what streamed, so there is never a duplicated copy).
      if (ev.message) {
        if (last?.kind === 'text') {
          const cur = last.content;
          if (ev.message.startsWith(cur)) {
            last.content = ev.message; // final content extends/supersedes the streamed prefix
          } else if (!cur.startsWith(ev.message)) {
            last.content = ev.message; // regenerated / diverged: supersede, no duplication
          }
          // else cur.startsWith(ev.message): streamed is fuller (clipped reply) -> keep it
        } else {
          blocks.push({ kind: 'text', content: ev.message ?? '' });
        }
      }
    } else if (ev.type === 'turn-cancelled') {
      settle();
      settleInterrupted();
    } else if (ev.type === 'text-reset') {
      // a stream retry discards ONLY the partial assistant rendering, never the whole history:
      // drop the trailing in-progress text/thought so prior turns stay visible.
      while (blocks.length > 0) {
        const b = blocks[blocks.length - 1];
        if (b.kind === 'text' || b.kind === 'thought') blocks.pop();
        else break;
      }
    }
  }
  return blocks;
}

/**
 * Resolves the tool card a stream/finished event targets: the running card whose {@code toolOrdinal}
 * matches the event's {@code data.ordinal} when the runner stamped one, else the most recently started
 * card of that tool name (the name-deque topology). This makes repeated same-name tools land on their
 * own card even when a sibling is still streaming.
 */
function ordinalBlock(blocks: Block[], runningTools: Map<string, number[]>, ev: WebEvent): Block | undefined {
  const stack = runningTools.get(ev.message ?? '');
  if (!stack || stack.length === 0) return undefined;
  const ord = ev.data?.ordinal;
  if (typeof ord === 'number' && ord > 0) {
    for (let i = stack.length - 1; i >= 0; i--) {
      if (blocks[stack[i]]?.toolOrdinal === ord) return blocks[stack[i]];
    }
  }
  return blocks[stack[stack.length - 1]];
}

/** How many blocks are always rendered from the tail before pagination kicks in. */
export const DEFAULT_WINDOW_BLOCKS = 4096;

/** Chunk size of one "Load earlier" pagination step. */
export const LOAD_EARLIER_CHUNK = 256;

/**
 * The rendered window: the last {@code visible} blocks plus at most one partial tool card before
 * them (a tool-started just outside the window would otherwise render as stuck "working" - the
 * card is atomic and must not be split). Returns [firstBlockToRender, hadToExtend].
 */
export function renderWindow(blocks: Block[], visible: number): { start: number; extended: boolean } {
  if (blocks.length <= visible) {
    return { start: 0, extended: false };
  }
  let start = blocks.length - visible;
  // a tool block that started right before the window belongs to it (its finished/stream events
  // are inside the window): extend the window so the card is complete
  if (start > 0 && blocks[start - 1].kind === 'tool' && blocks[start - 1].state === 'running') {
    start -= 1;
  }
  return { start, extended: start > 0 };
}