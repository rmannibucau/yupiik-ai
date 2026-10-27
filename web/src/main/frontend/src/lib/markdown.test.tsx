import { describe, expect, it } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { normalizeMarkdown, hasOpenInlineSpan, stripEchoedRefs } from './markdown';

// render the normalized source through the same markdown pipeline ChatPage uses and return the
// static HTML, so the assertions cover the user-visible outcome (not just the normalizer).
function renderMd(src: string): string {
  return renderToStaticMarkup(
    <ReactMarkdown remarkPlugins={[remarkGfm]}>{normalizeMarkdown(src)}</ReactMarkdown>,
  );
}

describe('normalizeMarkdown', () => {
  it("keeps well-formed inline code intact: `foo`, `bar` and `dummy` - the comma never lands in code", () => {
    const src = "`foo`, `bar` and `dummy`";
    expect(normalizeMarkdown(src)).toBe(src);
    const html = renderMd(src);
    // three distinct code spans, the separating commas and "and" stay outside the code
    expect(html).toContain('<code>foo</code>');
    expect(html).toContain('<code>bar</code>');
    expect(html).toContain('<code>dummy</code>');
    expect(html).not.toContain('<code>foo,</code>');
    expect(html).not.toContain('`,</code>');
  });

  it("keeps the leading character of a code span: `mvn install` never loses its m", () => {
    const src = "`mvn install`";
    expect(normalizeMarkdown(src)).toBe(src);
    expect(renderMd(src)).toContain('<code>mvn install</code>');
  });

  it("renders **BUILD SUCCESS** as bold, not literal asterisks", () => {
    const src = "**BUILD SUCCESS**";
    expect(normalizeMarkdown(src)).toBe(src);
    const html = renderMd(src);
    expect(html).toContain('<strong>BUILD SUCCESS</strong>');
    expect(html).not.toContain('**BUILD SUCCESS**');
  });

  it('collapses interior whitespace in bold with spaces on both sides (Issue #6)', () => {
    const src = "** BUILD SUCCESS ** with `mvn install`";
    expect(normalizeMarkdown(src)).toBe("**BUILD SUCCESS** with `mvn install`");
    expect(renderMd(src)).toContain('<strong>BUILD SUCCESS</strong>');
  });

  it('collapses a stray space after the opener only: ** 1 (jre placement):** (Issue #6)', () => {
    const src = "** 1 (jre placement):** the `mvn install` step";
    expect(normalizeMarkdown(src)).toBe("**1 (jre placement):** the `mvn install` step");
    expect(renderMd(src)).toContain('<strong>1 (jre placement):</strong>');
  });

  it('never changes the delimiter character count', () => {
    for (const src of [
      "`foo`, `bar` and `dummy`",
      "`mvn install` and **BUILD SUCCESS**",
      "** 1 (jre placement):** then `x`",
      "plain text `a`, ` b`, `c`, `d `",
    ]) {
      const out = normalizeMarkdown(src);
      const backticks = (s: string) => (s.match(/`/g) || []).length;
      const stars = (s: string) => (s.match(/\*\*/g) || []).length;
      expect(backticks(out)).toBe(backticks(src));
      expect(stars(out)).toBe(stars(src));
    }
  });

  it('renders the settled session text with the correct code spans (regression: the inverted pairing)', () => {
    // The exact assistant prose that was seen inverted mid-stream in session s1a135cadfe0. Once the
    // block settles (balanced backticks), every intended code token must render as its own <code>,
    // and surrounding prose must NOT be pulled into a bogus span.
    const src =
      "So the branch is built on `8d41c8fd` which is 3 commits behind master. The branch also " +
      '**already merged** the destroy-order work (from `dev/destory-order`), which has since been ' +
      'merged to master as separate commits (`535b9a8a`, `4367a41d`).';
    const html = renderMd(src);
    expect(html).toContain('<code>8d41c8fd</code>');
    expect(html).toContain('<code>dev/destory-order</code>');
    expect(html).toContain('<code>535b9a8a</code>');
    expect(html).toContain('<code>4367a41d</code>');
    // the surrounding prose stays plain - no code span swallows the between-text: only the four
    // intended tokens may appear as <code> (the count catches any spurious pairing).
    expect(html.match(/<code>/g)).toHaveLength(4);
    // the bold "already merged" renders bold, not as literal ** nor swallowed into a code span
    expect(html).toContain('<strong>already merged</strong>');
    expect(html).not.toContain('<code><strong>already merged</strong></code>');
  });

  it('never treats a closing ** followed by a space as a new opener (regression: missing spaces)', () => {
    // The old SPACED_STRONG matched across adjacent bold spans: it saw the closing `**` of `before`
    // (followed by " the") as an opener and paired it with the next `**`, stripping the space after
    // each closer. Reproduced `**before** the split` -> `**before**the split` plus mispaired pipes.
    const src =
      "The baseline is **before** the split. Today, on `oratuxb3-b`, `R` and `R2D2` live in the " +
      "**same database** (same Oracle instance). It is **out of scope**.";
    // must be byte-identical: a well-formed multi-bold + inline-code sentence is never rewritten
    expect(normalizeMarkdown(src)).toBe(src);
    const html = renderMd(src);
    // every space after a closing ** is preserved
    expect(html).toContain('<strong>before</strong> the split');
    expect(html).toContain('the <strong>same database</strong> (same Oracle');
    expect(html).toContain('It is <strong>out of scope</strong>.');
    expect(html).not.toContain('<strong>before</strong>the');
    // inline code stays plain, not folded into any bogus span (three intended tokens)
    expect(html.match(/<code>/g)).toHaveLength(3);
  });
});

describe('stripEchoedRefs', () => {
  it('strips a whole echoed seam line with its trailing newline (the line pattern removes the tag line, surrounding blank lines survive)', () => {
    expect(stripEchoedRefs('Let me run the proper typecheck.\n\n@535@\n')).toBe('Let me run the proper typecheck.\n\n');
  });

  it('strips an inline tag glued to prose (the pattern consumes the space before the tag)', () => {
    expect(stripEchoedRefs('the answer @12@ is done')).toBe('the answer is done');
  });

  it('strips a seam split across streamed deltas once the block re-formed it', () => {
    // the reducer appended the two deltas into one block content before the render chokepoint
    expect(stripEchoedRefs('answer @5' + '35@')).toBe('answer');
  });

  it('strips b-prefixed and blocked tags and the optional priority suffix', () => {
    expect(stripEchoedRefs('@b21@ [high]\nreal prose')).toBe('real prose');
    // the leading space before the tag is consumed by the pattern, the trailing one survives
    expect(stripEchoedRefs('x @blocked@ y')).toBe('x y');
  });

  it('keeps a lone @ and non-tag text untouched', () => {
    expect(stripEchoedRefs('user@example.com and @ alone')).toBe('user@example.com and @ alone');
  });
});

describe('hasOpenInlineSpan', () => {
  it('reports no open span for balanced prose and closed code spans', () => {
    expect(hasOpenInlineSpan('')).toBe(false);
    expect(hasOpenInlineSpan('plain prose')).toBe(false);
    expect(hasOpenInlineSpan('`balanced` inline code')).toBe(false);
    expect(hasOpenInlineSpan('`a` and `b` and `c`')).toBe(false);
    expect(hasOpenInlineSpan('a ``double`` span')).toBe(false);
  });

  it('reports an open span when an inline-code closer has not streamed yet', () => {
    expect(hasOpenInlineSpan('verify `hop-transform-drools` and `plugins')).toBe(true);
    expect(hasOpenInlineSpan('still open `foo')).toBe(true);
    // a 2-backtick span left open
    expect(hasOpenInlineSpan('open ``double')).toBe(true);
  });

  it('never treats a 3+ backtick run at line start as an inline span (fenced block)', () => {
    // fully-streamed fence: opening + closing (even count) is never "unbalanced"
    expect(hasOpenInlineSpan('```java\nint x = 1; // ` not inline\n```')).toBe(false);
    // HALF-streamed fence: only the opener has arrived; it must NOT look like an open inline span,
    // otherwise the whole fenced block would be held from streaming (the regression to avoid).
    expect(hasOpenInlineSpan('```java\nint x = 1; // streaming…')).toBe(false);
    // tilde fence too (remark-gfm)
    expect(hasOpenInlineSpan('~~~bash\necho hi\n~~~')).toBe(false);
  });

  it('does not span open/close across a fenced block boundary', () => {
    // a single backtick before the fence and the close after the fence are separate, not one span
    expect(hasOpenInlineSpan('`x` then\n```\n`literal`\n```\nthen `y`')).toBe(false);
    expect(hasOpenInlineSpan('`x` then\n```\ncode\n```\nend `y')).toBe(true); // y's closer not streamed
  });
});

describe('live-streaming inline-code hold (freeinference fragment pattern)', () => {
  // A deterministic fragment stream shaped like what deepseek-v4-flash on freeinference actually
  // sends: tiny chunks (2-25 chars) that split INSIDE inline-code spans ~40% of the time (backticks
  // arrive fragmented across several SSE text-delta events). Historically yca rendered every
  // intermediate fragment through ReactMarkdown, so any fragment ending mid-span produced an
  // inverted pairing (prose swallowed into one oversized <code>). This test drives the same
  // fragments through the hold-on-open-span renderer used by useDebouncedMarkdown and asserts the
  // user-visible result never shows that regression, while the settled text stays byte-perfect.
  const realStream = [
    'To verify ',
    '`hop-transform-drools` ',
    'resolves, run ',
    '`mvn clean install` ',
    'with the ',
    '`build` ',
    'goal',
    "'s ",
    '`plugins`',
    '/',
    '`transforms` ',
    'param, then check ',
    '`plugins/transforms/<id>/` ',
    'contains the ',
    '`<transforms><transform>',
    'org.apache.hop:',
    'hop-transform-drools:',
    '2.19.0',
    '</transform></transforms>`',
    ' ',
    'XML snippet',
    ' ',
    'and its ',
    '`jandex` ',
    'is present.',
  ];

  // mirror the hook decision: while `writing`, keep the last render unless the fragment's inline
  // backticks are balanced (no open span) - then advance; the final settled content always renders.
  function writingView(): string[] {
    const held: string[] = [];
    let stable = '';
    let acc = '';
    const candidate = (s: string) => s; // would be normalizeMarkdown(render source)
    for (const frag of realStream) {
      acc += frag;
      if (hasOpenInlineSpan(acc)) {
        held.push(stable); // hold the last balanced render
      } else {
        stable = candidate(acc);
        held.push(stable);
      }
    }
    // settled: always render the full accumulated content
    held.push(candidate(acc));
    return held;
  }

  it('never draws prose swallowed into an oversized inline <code> at any writing boundary', () => {
    const views = writingView();
    for (const v of views) {
      const html = renderMd(v);
      const codes = [...html.matchAll(/<code>([^<]*)<\/code>/g)].map((m) => m[1]);
      for (const c of codes) {
        // a token swallowed into code spans a sentence boundary or mixes prose with the token;
        // legit tokens are compact (no 'either ... or' prose run, at most a handful of words), while
        // a mispair is long prose. The one long legit token here is the ~86-char <transforms> snippet.
        expect(c.length).toBeLessThan(120);
        expect(c.includes(' either ') || c.split(' ').length > 6).toBe(false);
      }
      // no trailing dangling backtick floats in the rendered output at a writing boundary
      expect(codes.filter((c) => c.trim() === '`' || /^\w+$/.test(c) && c.includes('`')).length).toBe(0);
    }
  });

  it('shows the full settled text with every intended code span when writing stops', () => {
    const settled = realStream.join('');
    const html = renderMd(settled);
    expect(html).toContain('<code>hop-transform-drools</code>');
    expect(html).toContain('<code>build</code>');
    expect(html).toContain('<code>plugins</code>');
    expect(html).toContain('<code>transforms</code>');
    expect(html).toContain('<code>plugins/transforms/&lt;id&gt;/</code>');
    expect(html).toContain('<code>jandex</code>');
    // the XML snippet is one inline code span, and "param, then check" stays plain prose
    expect(html).toContain('<code>&lt;transforms&gt;&lt;transform&gt;org.apache.hop:hop-transform-drools:2.19.0&lt;/transform&gt;&lt;/transforms&gt;</code>');
    expect(html).toContain('param, then check <code>');
    // exactly the eight intended tokens render as their own <code> span - no prose folded in
    // (hop-transform-drools, mvn clean install, build, plugins, transforms, plugins/transforms/<id>/,
    //  the <transforms> XML snippet, and jandex)
    expect(html.match(/<code>/g)).toHaveLength(8);
  });

  it('still streams a fenced code block live instead of holding it blank', () => {
    // a fenced block whose opener has arrived but the closer has not: hasOpenInlineSpan must be false
    // (fence excluded from inline balancing), so the renderer advances rather than freezes the stream.
    const halfFence = '```java\npublic void run() {\n  log.info("streaming");\n';
    expect(hasOpenInlineSpan(halfFence)).toBe(false);
    const html = renderMd(halfFence);
    // the fence must render (not be held until the closing ``` arrives)
    expect(html).not.toBe('');
    expect(html.length).toBeGreaterThan(0);
  });
});