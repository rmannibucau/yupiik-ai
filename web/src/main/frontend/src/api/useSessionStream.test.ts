import { describe, expect, it } from 'vitest';
import { foldLiveOutChars, foldStatus, type WebEvent } from './useSessionStream';

function ev(type: string, message?: string | null): WebEvent {
  return { type, sessionId: 's1', message: message ?? null } as WebEvent;
}

function btwEv(data: Record<string, unknown>, message?: string | null): WebEvent {
  return { type: 'btw', sessionId: 's1', message: message ?? null, data } as WebEvent;
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
});