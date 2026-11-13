// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WebEvent } from '../api/useSessionStream';

const state = vi.hoisted(() => ({ events: [] as WebEvent[], busy: false, bootstrapped: true, queued: [] as string[], todos: [] as { content: string; status: string }[] }));
const rpc = vi.hoisted(() => vi.fn(async (method: string, params: Record<string, unknown> = {}): Promise<unknown> => {
  if (method === 'session.todos') { return state.todos; }
  if (method === 'session.metadata') { return { mode: 'plan', mcpServers: [{ name: 'Remote MCP', state: 'ENABLED', transport: 'http', url: 'https://mcp.example.test/api' }] }; }
  if (method === 'session.queuedList') { return { prompts: state.queued }; }
  if (method === 'session.queuedCount') { return { count: state.queued.length }; }
  if (method === 'slash.complete') { return [{ label: 'Commands', replaceStart: 0, replaceEnd: String(params.text).length, items: ['/help', '/model'] }]; }
  return { mode: 'plan' };
}));
vi.mock('../api/rpc', () => ({ call: rpc, onServerRequest: () => () => {} }));
vi.mock('../api/useSessionStream', async (importOriginal) => ({ ...await importOriginal<typeof import('../api/useSessionStream')>(), useSessionStream: () => ({ events: state.events, connected: true,
  bootstrapped: state.bootstrapped, reconnecting: false, status: { busy: state.busy }, moreReplayExists: false, loadEarlier: () => {} }) }));
import { ChatPage } from './ChatPage';

let host: HTMLDivElement;
let root: Root;
const event = (type: string, message: string, data?: Record<string, unknown>): WebEvent => ({ type, message, data, sessionId: 'test' });
function render() {
  act(() => root.render(<MemoryRouter><ChatPage activeSession="test" openSession={() => {}} windowBlocks={2} /></MemoryRouter>));
}
function button(label: string) {
  const element = [...host.querySelectorAll('button')].find((entry) => entry.textContent?.trim() === label);
  expect(element, `button ${label}`).toBeDefined();
  return element!;
}
function type(text: string) {
  act(() => {
    const input = host.querySelector('textarea')!;
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(input, text);
    input.setSelectionRange(text.length, text.length);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function settle() { await act(async () => { await new Promise((resolve) => setTimeout(resolve, 15)); }); }
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  Element.prototype.scrollIntoView = vi.fn();
  rpc.mockClear(); state.events = []; state.busy = false; state.bootstrapped = true; state.queued = []; state.todos = [];
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); host.remove(); vi.unstubAllGlobals(); });

describe('mounted external-store chat integration', () => {
  it('expands sidebar MCP connection details and applies enabled-state colors', async () => {
    render(); await settle();
    act(() => host.querySelector<HTMLButtonElement>('[title="Expand servers"]')!.click());
    const panel = host.querySelector('[data-slot="mcp-server-panel"]')!;
    const server = panel.querySelector<HTMLButtonElement>('button[aria-expanded]')!;
    expect(server.getAttribute('aria-expanded')).toBe('false');
    expect(panel.querySelector('[data-slot="mcp-status-icon"]')?.getAttribute('class')).toContain('--yca-success');
    act(() => server.click());
    expect(panel.textContent).toContain('https://mcp.example.test/api');
    expect(server.getAttribute('aria-expanded')).toBe('true');
    act(() => server.click());
    expect(panel.textContent).not.toContain('https://mcp.example.test/api');
  });
  it('keeps the collapsed todo summary inside the header with progress visible and full text available', async () => {
    state.todos = [{ content: 'Finished task', status: 'completed' }, { content: 'Current task with a long description', status: 'in_progress' }];
    render(); await settle();
    const trigger = host.querySelector<HTMLButtonElement>('[title="Collapse todos"]')!;
    expect(trigger).not.toBeNull();
    act(() => trigger.click());
    const summary = host.querySelector('[data-slot="todo-collapsed-summary"]')!;
    expect(summary.parentElement).toBe(trigger);
    expect(summary.textContent).toBe('Current task with a long description');
    expect(summary.getAttribute('title')).toBe(summary.textContent);
    expect(summary.classList.contains('truncate')).toBe(true);
    expect(trigger.textContent).toContain('1/2');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(host.querySelector('[data-slot="todo-list"]')).toBeNull();
    act(() => trigger.click());
    expect(host.querySelector('[data-slot="todo-collapsed-summary"]')).toBeNull();
    expect(host.querySelector('[data-slot="todo-list"]')?.textContent).toContain('Finished task');
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
  });
  it('keeps oversized malformed arguments available without unbounded runtime JSON recovery', async () => {
    const args = '{"content":"' + 'x'.repeat(1_000_001);
    state.events = [event('user', 'inspect'), event('tool-started', 'write_file', { ordinal: 1, args })];
    render(); await settle();
    expect(host.querySelector('[data-slot="tool-call"]')).not.toBeNull();
    act(() => host.querySelector<HTMLButtonElement>('[data-slot="tool-call-trigger"]')!.click());
    expect(host.textContent).toContain('too large for a structured preview');
    expect(state.events[1].data?.args).toBe(args);
  });
  it('keeps bootstrap blocking the composer until the authoritative snapshot arrives', async () => {
    state.bootstrapped = false; render(); await settle();
    expect(host.textContent).toContain('Loading conversation');
    expect(host.querySelector('textarea')!.readOnly).toBe(true);
    expect(button('Send').disabled).toBe(true);
    state.bootstrapped = true; render(); await settle();
    expect(host.querySelector('textarea')!.readOnly).toBe(false);
  });
  it('dispatches a new prompt through YCA and still queues while busy', async () => {
    state.busy = true; render(); await settle(); type('additional prompt');
    await act(async () => button('Send').click());
    expect(rpc).toHaveBeenCalledWith('session.prompt', { id: 'test', prompt: 'additional prompt' });
    expect(host.querySelector('textarea')!.value).toBe('');
    await act(async () => button('■ Stop').click());
    expect(rpc).toHaveBeenCalledWith('session.cancel', { id: 'test' });
  });
  it('retains a tool disclosure and node identity when reasoning is hidden and history prepends', async () => {
    state.events = [event('user', 'latest'), event('tool-started', 'read', { ordinal: 1, args: '{"path":"file.txt"}' }),
      event('tool-finished', 'read', { ordinal: 1, output: 'exact output', success: true })];
    render(); await settle();
    const trigger = host.querySelector<HTMLButtonElement>('[data-slot="tool-call-trigger"]')!;
    act(() => trigger.click());
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    state.events = [event('user', 'earlier'), event('text-delta', 'earlier reply'), event('turn-done', ''), ...state.events];
    render(); await settle();
    expect(host.querySelector('[data-slot="tool-call-trigger"]')).toBe(trigger);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
  });
  it('restores the draft and displays a retryable dispatch failure', async () => {
    render(); await settle();
    rpc.mockImplementationOnce(async () => { throw new Error('offline'); });
    type('do not lose this');
    await act(async () => button('Send').click());
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('offline');
    expect(host.querySelector('textarea')!.value).toBe('do not lose this');
  });
  it('loads earlier display blocks without replacing the backend log', async () => {
    state.events = [event('user', 'first'), event('text-delta', 'first answer'), event('turn-done', ''), event('user', 'last'), event('text-delta', 'last answer')];
    render(); await settle();
    const before = JSON.stringify(state.events);
    const earlier = [...host.querySelectorAll('button')].find((entry) => entry.textContent?.includes('Load earlier'))!;
    act(() => earlier.click()); await settle();
    expect(host.textContent).toContain('first answer');
    expect(JSON.stringify(state.events)).toBe(before);
  });
  it('selects a slash completion with Tab without dispatching it', async () => {
    render(); await settle(); type('/he');
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 170)); });
    expect(host.querySelector('[role="listbox"]')).not.toBeNull();
    const input = host.querySelector('textarea')!;
    await act(async () => { input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })); });
    expect(input.value).toBe('/help');
    expect(rpc).not.toHaveBeenCalledWith('session.slash', expect.anything());
    expect(host.querySelector('[role="listbox"]')).toBeNull();
    await act(async () => { input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })); });
    expect(rpc).toHaveBeenCalledWith('session.slash', { id: 'test', text: '/help' });
  });
  it('dismisses completions on Escape until text changes and ignores IME Enter', async () => {
    render(); await settle(); type('/he');
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 170)); });
    const input = host.querySelector('textarea')!;
    act(() => input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })));
    expect(host.querySelector('[role="listbox"]')).toBeNull();
    act(() => input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true })));
    expect(rpc).not.toHaveBeenCalledWith('session.slash', expect.anything());
  });
  it('defaults streaming reasoning to collapsed, preserves a manual fold and leaves the source unchanged', async () => {
    state.events = [event('user', 'question'), event('thinking-delta', 'private reasoning')];
    render(); await settle();
    const trigger = host.querySelector<HTMLButtonElement>('[data-slot="reasoning-trigger"]')!;
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(host.textContent).toContain('Thoughts: Collapsed');
    act(() => trigger.click());
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    act(() => trigger.click());
    state.events = [...state.events, event('thinking-delta', ' more')]; render(); await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    state.events = [...state.events, event('turn-done', 'answer')]; render(); await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(state.events[1].message).toBe('private reasoning');
  });
  it('offers hidden, collapsed and expanded thoughts while preserving backend show/hide commands', async () => {
    state.events = [event('user', 'question'), event('thinking-delta', 'private reasoning'), event('turn-done', '')];
    render(); await settle();
    const choose = async (label: string) => {
      const trigger = [...host.querySelectorAll<HTMLButtonElement>('button')].find(entry => entry.textContent?.startsWith('Thoughts:'))!;
      act(() => trigger.click());
      const menu = host.querySelector('[role="menu"][aria-label="Thoughts visibility"]')!;
      const option = [...menu.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')].find(entry => entry.textContent?.endsWith(label))!;
      await act(async () => option.click());
    };
    await choose('Expanded');
    expect(host.querySelector('[data-slot="reasoning-trigger"]')?.getAttribute('aria-expanded')).toBe('true');
    expect(rpc).toHaveBeenLastCalledWith('session.slash', { id: 'test', text: '/thinking show' });
    // A backend boolean update must not undo the locally selected disclosure posture.
    state.events = [...state.events, event('settings', '', { showThinking: true })]; render(); await settle();
    expect(host.textContent).toContain('Thoughts: Expanded');
    await choose('Collapsed');
    expect(host.querySelector('[data-slot="reasoning-trigger"]')?.getAttribute('aria-expanded')).toBe('false');
    await choose('Hidden');
    expect(host.querySelector('[data-slot="reasoning-root"]')).toBeNull();
    expect(rpc).toHaveBeenLastCalledWith('session.slash', { id: 'test', text: '/thinking hide' });
    await choose('Expanded');
    expect(host.querySelector('[data-slot="reasoning-trigger"]')?.getAttribute('aria-expanded')).toBe('true');
    expect(state.events[1].message).toBe('private reasoning');
  });
  it('honors slash-driven thought visibility and restores manual disclosure after hide/show', async () => {
    state.events = [event('user', 'question'), event('thinking-delta', 'private reasoning')];
    render(); await settle();
    act(() => host.querySelector<HTMLButtonElement>('[data-slot="reasoning-trigger"]')!.click());
    state.events = [...state.events, event('settings', '', { showThinking: false })]; render(); await settle();
    expect(host.querySelector('[data-slot="reasoning-root"]')).toBeNull();
    expect(host.textContent).toContain('Thoughts: Hidden');
    state.events = [...state.events, event('settings', '', { showThinking: true })]; render(); await settle();
    expect(host.textContent).toContain('Thoughts: Collapsed');
    expect(host.querySelector('[data-slot="reasoning-trigger"]')?.getAttribute('aria-expanded')).toBe('true');
  });
  it.each([false, true])('does not submit a draft when removing a queued prompt (confirmation %s)', async (confirmed) => {
    state.queued = ['queued prompt'];
    vi.stubGlobal('confirm', vi.fn(() => confirmed));
    render(); await settle(); type('unsent draft'); rpc.mockClear();
    await act(async () => host.querySelector<HTMLButtonElement>('[title="Remove this queued prompt"]')!.click());
    expect(rpc).not.toHaveBeenCalledWith('session.prompt', expect.anything());
    expect(host.querySelector('textarea')!.value).toBe('unsent draft');
    expect(rpc.mock.calls.some(([method]) => method === 'session.queuedRemove')).toBe(confirmed);
    vi.restoreAllMocks();
  });
  it('restores manual tool disclosure after hide/show without retaining hidden raw output DOM', async () => {
    state.events = [event('user', 'latest'), event('tool-started', 'read', { ordinal: 1 }), event('tool-finished', 'read', { ordinal: 1, output: 'output', success: true })];
    render(); await settle();
    act(() => host.querySelector<HTMLButtonElement>('[data-slot="tool-call-trigger"]')!.click());
    state.events = [...state.events, event('settings', '', { showToolOutput: 'hidden' })]; render(); await settle();
    expect(host.querySelector('[data-slot="tool-call"]')).toBeNull();
    state.events = [...state.events, event('settings', '', { showToolOutput: 'collapsed' })]; render(); await settle();
    expect(host.querySelector('[data-slot="tool-call-trigger"]')?.getAttribute('aria-expanded')).toBe('true');
  });
  it('flushes a continuously streaming open inline span at the maximum hold interval', async () => {
    vi.useFakeTimers();
    try {
      state.events = [event('user', 'question'), event('text-delta', 'prefix `open')];
      render();
      for (let i = 0; i < 22; i++) {
        state.events = [...state.events, event('text-delta', ' x')];
        await act(async () => { render(); await vi.advanceTimersByTimeAsync(100); });
      }
      expect(host.querySelector('[data-yca-kind="text"]')?.textContent).toContain('x x x x');
    } finally {
      vi.useRealTimers();
    }
  });
});
