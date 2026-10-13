import { describe, expect, it } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { normalizeMarkdown } from './markdown';

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