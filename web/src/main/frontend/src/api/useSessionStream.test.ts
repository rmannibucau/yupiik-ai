import { describe, expect, it } from 'vitest';
import { foldLiveOutChars, foldStatus, bootstrapFromSnapshot, type WebEvent } from './useSessionStream';

function ev(type: string, message?: string | null): WebEvent {
  return { type, sessionId: 's1', message: message ?? null } as WebEvent;
}

function btwEv(data: Record<string, unknown>, message?: string | null): WebEvent {
  return { type: 'btw', sessionId: 's1', message: message ?? null, data } as WebEvent;
}

function usageEv(data: Record<string, unknown>, message?: string | null): WebEvent {
  return { type: 'usage-updated', sessionId: 's1', message: message ?? null, data } as WebEvent;
}

function statusEv(message: string | null, busy?: boolean): WebEvent {
  return { type: 'status', sessionId: 's1', message, data: busy === undefined ? {} : { busy } } as WebEvent;
}

describe('foldLiveOutChars - realtime out estimate', () => {
  it('accumulates streamed text and thinking characters', () => {
    let acc = 0;
    acc = foldLiveOutChars(acc, ev('text-delta', 'Hello '));
    acc = foldLiveOutChars(acc, ev('thinking-delta', 'reasoning '));
    acc = foldLiveOutChars(acc, ev('text-delta', 'world'));
    expect(acc).toBe('Hello '.length + 'reasoning '.length + 'world'.length);
  });

  it('ignores non-stream action events (tool, status, usage)', () => {
    const acc = foldLiveOutChars(42, ev('tool-finished'));
    expect(acc).toBe(42);
  });

  it('resets to 0 at a turn boundary', () => {
    let acc = 100;
    acc = foldLiveOutChars(acc, ev('turn-done'));
    expect(acc).toBe(0);
  });

  it('resets on error and user turns too', () => {
    expect(foldLiveOutChars(50, ev('error'))).toBe(0);
    expect(foldLiveOutChars(50, ev('user', 'new question'))).toBe(0);
    expect(foldLiveOutChars(50, ev('turn-cancelled'))).toBe(0);
  });

  it('null message contributes nothing', () => {
    expect(foldLiveOutChars(7, ev('text-delta', null))).toBe(7);
  });
});

describe('foldStatus - btw side-question folding (latest-wins single slot)', () => {
  it('folds a running btw start into status.btw', () => {
    const s = foldStatus([btwEv({ id: 1, running: true, question: 'what is xyz?' })]);
    expect(s.btw).toEqual({ id: 1, running: true, question: 'what is xyz?', answer: undefined, error: undefined });
  });

  it('replaces the running slot with the finalized answer on completion', () => {
    const events = [
      btwEv({ id: 1, running: true, question: 'q' }),
      btwEv({ id: 1, running: false, question: 'q', answer: 'the answer' }),
    ];
    const s = foldStatus(events);
    expect(s.btw).toEqual({ id: 1, running: false, question: 'q', answer: 'the answer', error: undefined });
  });

  it('folds an error completion', () => {
    const s = foldStatus([btwEv({ id: 2, running: true, question: 'q' }), btwEv({ id: 2, running: false, question: 'q', error: 'boom' })]);
    expect(s.btw?.error).toBe('boom');
    expect(s.btw?.running).toBe(false);
  });

  it('a newer /btw overrides the previous one (latest wins)', () => {
    const events = [
      btwEv({ id: 1, running: true, question: 'first' }),
      btwEv({ id: 2, running: true, question: 'second' }),
    ];
    const s = foldStatus(events);
    expect(s.btw?.id).toBe(2);
    expect(s.btw?.question).toBe('second');
    expect(s.btw?.running).toBe(true);
  });

  it('falls back to the event message as the question when data.question is absent (snapshot legacy)', () => {
    const s = foldStatus([btwEv({ id: 3, running: true }, 'legacy question')]);
    expect(s.btw?.question).toBe('legacy question');
  });

  it('leaves status.btw undefined when no btw event is present', () => {
    const s = foldStatus([ev('status', 'working'), ev('usage-updated', 'model')]);
    expect(s.btw).toBeUndefined();
  });

  it('a cleared btw event (StatusBar x -> session.btwClear) removes the slot instead of respawning it', () => {
    // the clear broadcast carries {id, cleared:true} with no running/question: it must clear the
    // pill + modal, NOT reconstruct a fresh running btw from the message (the respawn bug).
    const s = foldStatus([
      btwEv({ id: 1, running: true, question: 'what is xyz?' }),
      btwEv({ id: 1, cleared: true }, 'what is xyz?'),
    ]);
    expect(s.btw).toBeUndefined();
  });

  it('a clear after an answer also removes the slot', () => {
    const s = foldStatus([
      btwEv({ id: 1, running: true, question: 'q' }),
      btwEv({ id: 1, running: false, question: 'q', answer: 'the answer' }),
      btwEv({ id: 1, cleared: true }),
    ]);
    expect(s.btw).toBeUndefined();
  });
});

describe('foldStatus - live per-turn out/reasoning tokens', () => {
  it('reports the per-turn delta from cumulative usage while no turn-metrics yet', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 120, reasoningTokens: 40 }),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
    ]);
    expect(s.busy).toBe(true);
    expect(s.liveOutTokens).toBe(300);
    expect(s.liveReasoningTokens).toBe(90);
  });

  it('resets the baseline at each new turn (false -> true) so the delta is per-turn', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
      statusEv('ready', false),
      statusEv('working', true),
      usageEv({ completionTokens: 420, reasoningTokens: 110 }),
    ]);
    expect(s.liveOutTokens).toBe(120);
    expect(s.liveReasoningTokens).toBe(20);
  });

  it('a new turn drops the previous turn authoritative metrics so the live delta shows', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300 }),
      { type: 'turn-metrics', sessionId: 's1', message: null, data: { outputTokens: 300, reasoningTokens: 90 } } as WebEvent,
      statusEv('ready', false),
      statusEv('working', true),
      usageEv({ completionTokens: 340 }),
    ]);
    expect(s.outputTokens).toBeUndefined();
    expect(s.reasoningTokens).toBeUndefined();
    expect(s.liveOutTokens).toBe(40);
  });

  it('keeps counting from the same baseline across repeated busy:true activity frames mid-turn', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 100 }),
      statusEv('thinking...', true),
      usageEv({ completionTokens: 250 }),
      statusEv('calling bash', true),
    ]);
    expect(s.liveOutTokens).toBe(250);
    expect(s.liveReasoningTokens).toBeUndefined();
  });

  it('a turn-done ends the turn and the next usage is a fresh delta', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
      { type: 'turn-done', sessionId: 's1', message: 'done' } as WebEvent,
      statusEv('working', true),
      usageEv({ completionTokens: 380, reasoningTokens: 95 }),
    ]);
    expect(s.liveOutTokens).toBe(80);
    expect(s.liveReasoningTokens).toBe(5);
  });

  it('authoritative turn-metrics outputTokens still win over the live delta', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
      { type: 'turn-metrics', sessionId: 's1', message: null, data: { outputTokens: 300, reasoningTokens: 90 } } as WebEvent,
    ]);
    expect(s.outputTokens).toBe(300);
    expect(s.reasoningTokens).toBe(90);
    expect(s.liveOutTokens).toBe(300);
  });

  it('no live out when the provider reports no usage', () => {
    const s = foldStatus([statusEv('working', true)]);
    expect(s.liveOutTokens).toBeUndefined();
    expect(s.liveReasoningTokens).toBeUndefined();
  });

  it('a reloaded snapshot (replayed text only, no usage) still yields a log-derived out estimate', () => {
    // WebReplay replays only user/text/tool events - no usage-updated/turn-metrics. Without the
    // log-derived liveOutChars fallback the metrics bar would render an EMPTY token line after a
    // reload (all seven token sources null); with it, the replayed deltas give a non-zero "out".
    // A trailing turn-done (completed turn on reload) keeps the accumulated value for display.
    const s = foldStatus([
      ev('user', 'reload'),
      ev('text-delta', 'Old bursts were emitted and validated.'),
      ev('text-delta', ' Now the second burst.'),
      { type: 'turn-done', sessionId: 's1', message: null } as WebEvent,
    ]);
    expect(s.liveOutTokens).toBeUndefined(); // no provider usage replayed
    expect(s.completionTokens).toBeUndefined(); // no usage-updated replayed
    expect(s.liveOutChars).toBe('Old bursts were emitted and validated. Now the second burst.'.length);
  });

  it('a server-seeded synthetic usage-updated (snapshot seed) yields cumulative in/out immediately', () => {
    // WebSessionManager.snapshotFor now prepends a synthetic usage-updated carrying the session's
    // cumulative totals for reloaded tabs, so the metrics bar shows exact "in N · out N" on load.
    const s = foldStatus([usageEv({ promptTokens: 4000, completionTokens: 1200, reasoningTokens: 300, cacheReadTokens: 1200, contextPercent: 42 })]);
    expect(s.promptTokens).toBe(4000);
    expect(s.completionTokens).toBe(1200);
    expect(s.cacheReadTokens).toBe(1200);
    // no busy baseline yet, so the live out delta is the whole cumulative total
    expect(s.liveOutTokens).toBe(1200);
    expect(s.liveReasoningTokens).toBe(300);
  });

  it('cacheReadTokens stays absent when the provider publishes none', () => {
    const s = foldStatus([usageEv({ promptTokens: 4000, completionTokens: 1200 })]);
    expect(s.cacheReadTokens).toBeUndefined();
  });

  it('log-derived out chars start fresh at each new user turn', () => {
    const s = foldStatus([
      ev('user', 'first turn'),
      ev('text-delta', 'alpha '),
      { type: 'turn-done', sessionId: 's1', message: null } as WebEvent,
      ev('user', 'second turn'),
      ev('text-delta', 'beta gamma'),
    ]);
    expect(s.liveOutChars).toBe('beta gamma'.length);
  });
});

describe('bootstrapFromSnapshot - bootstrapped (history-loaded) transition', () => {
  it('a snapshot with history bootstraps', () => {
    expect(bootstrapFromSnapshot({ events: [ev('user', 'hi')] })).toBe(true);
  });

  it('an EMPTY snapshot ({events:[]}) still bootstraps - a fresh/empty session must clear the loader, never spin forever', () => {
    expect(bootstrapFromSnapshot({ events: [] })).toBe(true);
  });

  it('a snapshot frame missing the events key does NOT bootstrap (keeps the loader up)', () => {
    expect(bootstrapFromSnapshot({})).toBe(false);
  });

  it('malformed / non-object payloads do NOT bootstrap', () => {
    expect(bootstrapFromSnapshot(null)).toBe(false);
    expect(bootstrapFromSnapshot(undefined)).toBe(false);
    expect(bootstrapFromSnapshot('events')).toBe(false);
    expect(bootstrapFromSnapshot(7)).toBe(false);
  });
});