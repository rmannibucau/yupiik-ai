import { describe, expect, it } from 'vitest';
import { eventsToBlocks, renderWindow, latestDrainBoundary } from './eventLog';
import type { WebEvent } from './useSessionStream';

function ev(partial: Partial<WebEvent> & { type: string; sessionId: string }): WebEvent {
  return { message: null, data: undefined, ...partial } as WebEvent;
}

describe('eventsToBlocks', () => {
  it('accumulates text-delta events into a single assistant block', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'hi' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Hello ' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'world' }),
    ]);
    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toMatchObject({ kind: 'user', content: 'hi' });
    expect(blocks[1]).toMatchObject({ kind: 'text', content: 'Hello world' });
  });

  it('ignores todo-updated frames (they only trigger a sidebar reload)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'plan it' }),
      ev({ type: 'todo-updated', sessionId: 's1', data: { todos: [] } as never }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'done' }),
    ]);
    // todo-updated must not create a block nor disturb the transcript graph
    expect(blocks.map((b) => b.kind)).toEqual(['user', 'text']);
  });

  it('ignores btw events (they live in the status-bar pill + modal, never in the transcript)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'tell me something' }),
      ev({ type: 'btw', sessionId: 's1', message: 'what is xyz?', data: { id: 1, running: true, question: 'what is xyz?' } }),
      ev({ type: 'btw', sessionId: 's1', message: 'what is xyz?', data: { id: 1, running: false, question: 'what is xyz?', answer: 'something' } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'the main answer' }),
    ]);
    // btw frames must not create a block nor disturb the transcript graph
    expect(blocks.map((b) => b.kind)).toEqual(['user', 'text']);
    expect(blocks[1]).toMatchObject({ kind: 'text', content: 'the main answer' });
  });
});

describe('eventsToBlocks - repeated same-name tools bind to their own card via ordinal', () => {
  it('finished for bash#2 does not finish bash#1 and both settle done', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 2 } }),
      ev({ type: 'tool-stream', sessionId: 's1', message: 'bash', data: { ordinal: 2, fragment: 'out of #2' } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 2, output: 'done', success: true } }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools).toHaveLength(2);
    expect(tools[0]).toMatchObject({ toolOrdinal: 1, state: 'running' });
    expect(tools[1]).toMatchObject({ toolOrdinal: 2, state: 'done', success: true, content: 'out of #2\n' });
  });

  it('finished with an ordinal matches the exact started card, not the name-deque head', () => {
    // start bash#1 and bash#2, finish bash#1 FIRST (out of order relative to the deque)
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 2 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'first', success: true } }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ toolOrdinal: 1, state: 'done' });
    expect(tools[1]).toMatchObject({ toolOrdinal: 2, state: 'running' });
  });
});

describe('eventsToBlocks - turn boundaries settle still-running tool cards as interrupted', () => {
  it('turn-cancelled marks a tool left running as interrupted instead of working', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 3 } }),
      ev({ type: 'turn-cancelled', sessionId: 's1' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools).toHaveLength(1);
    expect(tools[0]).toMatchObject({ state: 'interrupted' });
    expect(tools[0].state).not.toBe('running');
  });

  it('turn-done settles a still-running tool card as interrupted even without message content', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 1 } }),
      ev({ type: 'turn-done', sessionId: 's1', message: '' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'interrupted' });
  });

  it('error settles still-running tool cards as interrupted', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'error', sessionId: 's1', message: 'boom' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'interrupted' });
  });

  it('a tool-finished before the boundary still resolves to done (not interrupted)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'read_file', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'turn-cancelled', sessionId: 's1' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'done', success: true });
  });
});

describe('eventsToBlocks - turn-done supersedes the streamed tail (never appends)', () => {
  const full = 'Answer with `code1`, `code2` and **bold** text.';

  it('a trailing-char difference no longer duplicates the whole answer', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: full }),
      ev({ type: 'turn-done', sessionId: 's1', message: full + '\n' }),
    ]);
    const b = blocks[blocks.length - 1];
    expect(b.kind).toBe('text');
    // the authoritative final content supersedes the streamed copy - not appended next to it
    expect((b as { content: string }).content).toBe(full + '\n');
  });

  it('a streamed prefix is extended in place by the final content (no duplication)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: full.slice(0, 20) }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    const b = blocks[blocks.length - 1];
    expect((b as { content: string }).content).toBe(full);
  });

  it('a fuller streamed text (clipped provider reply) is kept, not truncated by turn-done', () => {
    const clip = full + ' MORE';
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: clip }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    const b = blocks[blocks.length - 1];
    expect((b as { content: string }).content).toBe(clip);
  });

  it('a multi-round turn does not bleed the final content into an earlier round block', () => {
    const r1 = 'Before the tool. ';
    const r2 = 'After: `z`.';
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: r1 }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'text-delta', sessionId: 's1', message: r2 }),
      ev({ type: 'turn-done', sessionId: 's1', message: r2 }),
    ]);
    const texts = blocks.filter((b) => b.kind === 'text').map((b) => b.content);
    expect(texts).toEqual([r1, r2]);
  });

  it('arriving after a tool card (last block not text) pushes a fresh text block', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    expect(blocks[blocks.length - 1]).toMatchObject({ kind: 'text', content: full });
  });

  it('streamed equal to final is a no-op (never duplicated)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: full }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    const b = blocks[blocks.length - 1];
    expect((b as { content: string }).content).toBe(full);
  });
});

describe('eventsToBlocks - writing flag drives the debounced markdown render', () => {
  it('stamps writing=true while text-delta keeps appending, false once a non-delta settles it', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'text-delta', sessionId: 's1', message: 'built on ' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '`8d41c8fd`' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
    ]);
    expect(blocks[0]).toMatchObject({ kind: 'text', writing: false });
  });

  it('leaves a still-streaming text block flagged writing=true at the tail', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'text-delta', sessionId: 's1', message: 'built on `8d4' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '1c8fd`' }),
    ]);
    expect(blocks[blocks.length - 1]).toMatchObject({ kind: 'text', writing: true });
  });
});

describe('eventsToBlocks - tool cards capture their args for language detection', () => {
  it('captures the raw arguments JSON from tool-started', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 1, args: '{"path":"Foo.java"}' } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'read_file', data: { ordinal: 1, output: 'class Foo {}', success: true } }),
    ]);
    const tool = blocks.find((b) => b.kind === 'tool') as { args?: string };
    expect(tool.args).toBe('{"path":"Foo.java"}');
  });

  it('omits args when tool-started carries none or a blank object', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
    ]);
    const tool = blocks.find((b) => b.kind === 'tool') as { args?: string };
    expect(tool.args).toBeUndefined();
  });
});

describe('renderWindow', () => {
  it('keeps everything when under the visible window', () => {
    const blocks = [1, 2, 3].map((n) => ({ kind: 'text' as const, content: String(n) }));
    expect(renderWindow(blocks, 1024)).toEqual({ start: 0, extended: false });
  });

  it('returns a start past the window when more blocks than visible', () => {
    const blocks = Array.from({ length: 20 }, (_, i) => ({ kind: 'text' as const, content: String(i) }));
    const r = renderWindow(blocks, 10);
    expect(r.extended).toBe(true);
    expect(r.start).toBe(10);
    expect(blocks.slice(r.start)).toHaveLength(10);
  });
});
describe('latestDrainBoundary', () => {
  it('returns null with no drain-relevant boundary present', () => {
    expect(latestDrainBoundary([
      ev({ type: 'user', sessionId: 's1', message: 'hi' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'ok' }),
    ])).toBeNull();
  });

  it('detects a prompt-integrated mid-stream even when it is NOT the last event (the queue-strip bug)', () => {
    // the loop keeps streaming status/text frames right after the prompt-integrated, so it is never
    // the tail - a tail-only check (the old behavior) missed it and left the queue block stale.
    const sig = latestDrainBoundary([
      ev({ type: 'user', sessionId: 's1', message: 'first' }),
      ev({ type: 'prompt-integrated', sessionId: 's1', message: 'queued one' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'answering…' }),
      ev({ type: 'usage-updated', sessionId: 's1' }),
    ]);
    expect(sig).toBe('prompt-integrated:s1:queued one');
  });

  it('prefers the most recent boundary scanning backward', () => {
    const sig = latestDrainBoundary([
      ev({ type: 'prompt-integrated', sessionId: 's1', message: 'older' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '…' }),
      ev({ type: 'prompt-integrated', sessionId: 's1', message: 'newer' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash' }),
    ]);
    expect(sig).toBe('prompt-integrated:s1:newer');
  });

  it('uses prompt text to keep distinct queued prompts distinct', () => {
    const a = latestDrainBoundary([ev({ type: 'prompt-integrated', sessionId: 's1', message: 'q1' })]);
    const b = latestDrainBoundary([ev({ type: 'prompt-integrated', sessionId: 's1', message: 'q2' })]);
    expect(a).not.toBe(b);
  });

  it('turn boundaries are keyed by type+sessionId (no message)', () => {
    const sig = latestDrainBoundary([
      ev({ type: 'status', sessionId: 's1' }),
      ev({ type: 'turn-done', sessionId: 's1' }),
    ]);
    expect(sig).toBe('turn-done:s1');
  });
});
