import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TurnMetrics } from './ChatPage';
import { eventsToBlocks } from '../api/eventLog';
import { dedupKey, type WebEvent } from '../api/useSessionStream';

const status = {
  turnLlmCalls: 2, turnThinkingMs: 1000, turnToolsRun: 3, turnToolSumMs: 400, turnToolWallMs: 200,
  sessionLlmCalls: 7, promptTokens: 100, outputTokens: 25,
};

describe('web slash command rendering', () => {
  it('honors metrics hide, turn, session, and density settings', () => {
    const render = (perTurn: boolean, session: boolean, full = false) =>
      renderToStaticMarkup(<TurnMetrics status={status} settings={{ perTurn, session, full }} />);
    expect(render(false, false)).toBe('');
    expect(render(true, false)).toContain('2 LLM');
    expect(render(true, false)).not.toContain('session:');
    expect(render(false, true)).toContain('session: 7 LLM calls');
    expect(render(false, true)).not.toContain('2 LLM');
    expect(render(true, true, false)).not.toContain('tools sum');
    expect(render(true, true, true)).toContain('tools sum');
  });

  it('clears the visible transcript while retaining later content', () => {
    const events: WebEvent[] = [
      { type: 'user', sessionId: 's', message: 'old question' },
      { type: 'text-delta', sessionId: 's', message: 'old answer' },
      { type: 'web-command', sessionId: 's', data: { command: 'clear' } },
    ];
    expect(eventsToBlocks(events)).toEqual([]);
    const next = eventsToBlocks([...events, { type: 'user', sessionId: 's', message: 'new question' }]);
    expect(next).toHaveLength(1);
    expect(next[0]).toMatchObject({ kind: 'user', content: 'new question' });
  });

  it('does not deduplicate repeated settings or repeated modal commands', () => {
    for (const type of ['settings', 'web-command']) {
      const event: WebEvent = { type, sessionId: 's', data: { command: 'subagents' } };
      expect(dedupKey(event, 1)).not.toBe(dedupKey(event, 2));
    }
  });
});
