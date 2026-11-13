// @vitest-environment happy-dom
import React, { act } from 'react';
import { readFileSync } from 'node:fs';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionMetadata } from './SessionMetadata';

const css = readFileSync('src/index.css', 'utf8');
let host: HTMLDivElement;
let root: Root;
let style: HTMLStyleElement;
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  style = document.createElement('style');
  style.textContent = css.slice(css.indexOf('.yca-session-metadata {'), css.indexOf('/* TUI-aligned Markdown;'));
  document.head.append(style);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  style.remove();
  vi.unstubAllGlobals();
});

function render() {
  act(() => root.render(<SessionMetadata sessionId="session-example" permissionMode="auto_edit" onSetProject={() => {}} onSetModel={() => {}} onSetProvider={() => {}} onSetPermissions={() => {}} />));
}

describe('sidebar metadata layout', () => {
  it('centers metadata labels and values and keeps the permission dot trailing', () => {
    render();
    expect(getComputedStyle(host.querySelector('.yca-metadata-row')!).alignItems).toBe('center');
    const select = host.querySelector('select[title="Tool permission level for this session"]')!;
    expect(select.nextElementSibling?.getAttribute('aria-hidden')).toBe('true');
    expect(select.parentElement?.lastElementChild).toBe(select.nextElementSibling);
    expect(getComputedStyle(host.querySelector('.yca-metadata-value')!).overflowWrap).toBe('anywhere');
    expect(getComputedStyle(select).minWidth).toBe('0');
  });

});
