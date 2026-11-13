// @vitest-environment happy-dom
import React, { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToolCard, ProgressiveText } from '../../components/ToolCard';
import { MarkdownContent } from '../../components/MarkdownContent';
import { CodeBlock } from '../../components/CodeBlock';
import type { Block } from '../../api/eventLog';

type ToolBlock = Extract<Block, { kind: 'tool' }>;
let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});
function render(node: React.ReactNode) {
  act(() => root.render(node));
}
function button(label: string): HTMLButtonElement {
  const found = host.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);
  if (!found) {
    throw new Error(`Missing button ${label}`);
  }
  return found;
}
function click(element: HTMLElement) {
  act(() => element.click());
}
function raw(label: string): HTMLDetailsElement {
  const found = [...host.querySelectorAll('details')].find((element) => element.querySelector('summary')?.textContent === label);
  if (!found) {
    throw new Error(`Missing disclosure ${label}`);
  }
  return found;
}
function open(details: HTMLDetailsElement) {
  act(() => {
    details.open = true;
    details.dispatchEvent(new Event('toggle'));
  });
}

describe('standalone assistant-ui rendering integration', () => {
  it('keeps ToolCard controlled, lazy and standalone and cleans up upstream scroll anchoring', () => {
    vi.useFakeTimers();
    const onToggle = vi.fn();
    const block: ToolBlock = { kind: 'tool', name: 'external', state: 'done', success: true, content: '', args: '{"secret":"value"}' };
    host.style.overflowY = 'auto';
    host.style.scrollbarWidth = 'thin';
    host.scrollTop = 73;
    render(<ToolCard block={block} collapsed onToggle={onToggle} />);
    const trigger = host.querySelector<HTMLButtonElement>('.yca-tool-header')!;
    expect(host.querySelector('[aria-label="Tool request"]')).toBeNull();
    click(trigger);
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(host.style.scrollbarWidth).toBe('none');
    host.scrollTop = 10;
    host.dispatchEvent(new Event('scroll'));
    expect(host.scrollTop).toBe(73);
    render(<ToolCard block={block} collapsed={false} onToggle={onToggle} />);
    expect(host.querySelector('[aria-label="Tool request"]')).not.toBeNull();
    expect(raw('Raw arguments').querySelector('code')).toBeNull();
    render(null);
    expect(host.style.scrollbarWidth).toBe('thin');
  });

  it('exposes exact raw arguments and output including the final character through bounded pages', () => {
    const args = '  {"content":"' + 'x'.repeat(8100) + '"} \r\n\t';
    const output = ' \t\r\n' + 'o'.repeat(8100) + '<script>TAIL</script>  \n';
    const block: ToolBlock = { kind: 'tool', name: 'external', state: 'done', content: output, args };
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
    for (const [label, expected] of [['Raw arguments', args], ['Raw output', output]]) {
      const details = raw(label);
      expect(details.querySelector('code')).toBeNull();
      open(details);
      let recovered = '';
      const more = button(`Show more ${label}`);
      for (let page = 0; page < 3; page++) {
        const value = details.querySelector('code')?.textContent ?? '';
        expect(value.length).toBeLessThanOrEqual(4000);
        recovered += value;
        if (!more.disabled) {
          click(more);
        }
      }
      expect(recovered).toBe(expected);
      expect(more.disabled).toBe(true);
      expect(details.querySelector('script')).toBeNull();
      click(button(`Show previous ${label}`));
      expect(details.querySelector('code')?.textContent).toBe(expected.slice(4000, 8000));
    }
  });

  it('does not style a page boundary inside a diff line as a new addition', () => {
    const text = 'x'.repeat(4000) + '+continuation\r\n+actual addition\n\\ No newline at end of file';
    render(<ProgressiveText text={text} label="Patch" diff />);
    click(button('Show more Patch'));
    const code = host.querySelector('code')!;
    expect(code.textContent).toBe(text.slice(4000));
    expect(code.firstElementChild?.getAttribute('data-type')).toBe('normal');
    expect(code.querySelectorAll('.yca-diff-add')).toHaveLength(1);
    expect(code.querySelector('.yca-diff-add')?.textContent).toBe('+actual addition\n');
  });

  it('updates standalone Markdown provider text and syntax without retaining stale code', () => {
    render(<MarkdownContent content={'```java\nclass Old {}\n```'} variant="assistant" />);
    expect(host.querySelector('.token.keyword')?.textContent).toBe('class');
    render(<MarkdownContent content={'```unknown\n\n\tnew  \n\n```'} variant="document" />);
    expect(host.querySelector('code')?.textContent).toBe('\n\tnew  \n\n');
    expect(host.textContent).not.toContain('Old');
    expect(host.querySelectorAll('pre')).toHaveLength(1);
    expect(host.querySelector('.aui-md')?.getAttribute('data-variant')).toBe('document');
    expect(host.querySelector('.token')).toBeNull();
  });

  it('updates the factory-supplied code/pre tags without losing aliases or whitespace', () => {
    render(<CodeBlock code={'class X {}\r\n'} language="java" className="first" />);
    render(<CodeBlock code={'# Heading\n\n'} language="md" className="second" />);
    expect(host.querySelector('pre')?.className).toBe('yca-code-block second');
    expect(host.querySelector('code')?.className).toBe('language-md');
    expect(host.querySelector('code')?.textContent).toBe('# Heading\n\n');
    expect(host.querySelector('.token')).not.toBeNull();
  });

  it('keeps patch file paging reachable through the reused request slot', () => {
    const patch = '*** Begin Patch\n' + Array.from({ length: 11 }, (_, index) => `*** Add File: file${index}\n+content${index}\n`).join('') + '*** End Patch';
    function Fixture() {
      const [collapsed, setCollapsed] = useState(true);
      return <ToolCard block={{ kind: 'tool', name: 'apply_patch', state: 'done', success: false, content: 'Rejected', args: JSON.stringify({ patch }) }} collapsed={collapsed} onToggle={() => setCollapsed((value) => !value)} />;
    }
    render(<Fixture />);
    click(host.querySelector('.yca-tool-header')!);
    for (let page = 0; page < 2; page++) {
      const next = [...host.querySelectorAll('button')].find((element) => element.textContent === 'Show more files')!;
      click(next);
    }
    expect(host.textContent).toContain('file10');
    expect(host.querySelectorAll('.yca-tool-patch-file')).toHaveLength(1);
    expect(host.textContent).toContain('not an applied diff');
    expect(host.textContent).toContain('Tool failed.');
  });
});
