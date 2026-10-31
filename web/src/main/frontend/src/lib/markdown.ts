/**
 * Markdown helpers: a conservative normalizer for the inline markdown LLMs occasionally emit slightly
 * malformed (a stray space right after a delimiter opener), so it renders cleanly through CommonMark
 * instead of being shown literally. Only same-line strong/emphasis pairs are touched; inline code,
 * multi-line prose and fenced code blocks are never disturbed (a lone backtick/`*`/`_` is too
 * ambiguous with prose punctuation to rewrite safely).
 *
 * The normalizer guarantees it never adds nor removes a delimiter character: it only ever strips
 * interior whitespace, so a bold span keeps its `**` pair intact.
 */

// a strong/emph delim pair (`**`/`__`) whose interior has wasty whitespace, e.g. "** BUILD SUCCESS **"
// or "** 1 (jre placement):**" (space after the opener, none before the closer). `**` pairs are
// unambiguous enough to rewrite; a lone `*`/`_` is not (prose asterisks, underscores, em-dashes).
//
// The opener must be LEFT-flanking (at line start or after whitespace) and right-flanked by non-space,
// and the closer must be right-flanked by non-space too. This guarantees a match is exactly ONE bold
// span and never spans across two adjacent `**...**` pairs: a *closing* `**` followed by a space (e.g.
// in `**before** the split`) can never be mistaken for an opener, so valid `**bold** word` prose is
// never disturbed and its space is preserved. The interior also excludes `*`/`_`/`` ` `` so a match
// can never swallow a neighbouring bold or an inline-code span.
const SPACED_STRONG = /(^|[ \t])(\*\*|__)([ \t]+)([^*_`\n]+?)([ \t]+)?\2(?!\S)/gm;
//                                 └lead┘ └open┘ └leadsp┘ └──inner──┘  └trail┘ └close┘ └after┘
// lead & after (negative lookahead) are line anchors that only fix the match boundaries and must be
// re-emitted verbatim; open/close are the same delim token (back-reference \2).

// A fenced code block delimiter is a run of >=3 backticks (or tildes). Such a run is NOT an
// inline-code delimiter, so it must never be counted toward an "unclosed backtick" heuristic: a
// fully-streamed fence has 3+3=6 backticks (even) but a HALF-streamed fence has just its opening 3
// (odd) and would otherwise look like a dangling inline span - yet it must never delay the render of
// a fenced block that is streaming in live. Content inside a fence is literal, so backticks there are
// ignored too. A run of >=3 may appear anywhere (CommonMark only opens fences at line start, but LLM
// output occasionally starts one inline after prose, e.g. "text ```java"); treating it as a fence
// avoids a spurious hold. The CLOSER must be a line that is essentially just the fence: <=3 leading
// spaces, a run of >= the opener's length of the SAME char, then only trailing whitespace.
const FENCE_RUN = /(`{3,}|~{3,})/;

/**
 * Reports whether `src` currently has an OPEN *inline* code span: an unclosed run of one or two
 * backticks (CommonMark code-span delimiters) outside any fenced block. A run of 3+ backticks (or
 * tildes) is treated as a fenced-block delimiter, not an inline span, so a half-streamed ```` ``` ````
 * fence never looks "unbalanced" and a fenced block keeps streaming live.
 *
 * The live renderer uses this to avoid ever drawing a streamed fragment whose inline-code backticks
 * are mid-span: CommonMark would mis-pair such a fragment and swallow the surrounding prose into one
 * oversized `<code>` (the inverted-pairing regression reported on freeinference, whose stream splits
 * inside code spans ~40% of the time). While {@code writing} a block, the {@code useDebouncedMarkdown}
 * hook therefore holds the last balanced render until the inline span closes, `writing` stops, or a
 * short time-cap elapses.
 *
 * A 1- or 2-backtick run opens a span and only a run of the SAME length closes it (CommonMark):
 * a mismatched run (e.g. a 2 run landing inside a 1-run span) leaves the span open.
 */
export function hasOpenInlineSpan(src: string): boolean {
  if (!src) return false;
  let open = 0; // run length currently open inline (0 = none); only 1 or 2 are inline delimiters
  let inFence = false;
  let fenceLen = 0;
  let fenceChar = '';

  const scanInline = (text: string) => {
    let i = 0;
    while (i < text.length) {
      if (text[i] !== '`') {
        i++;
        continue;
      }
      let run = 0;
      while (i + run < text.length && text[i + run] === '`') run++;
      if (run === 1 || run === 2) {
        if (open === 0) open = run;
        else if (open === run) open = 0;
        // else: mismatched run, span stays open
      }
      i += run;
    }
  };

  for (const line of src.split('\n')) {
    if (inFence) {
      // closer: a line that is only (up to 3 leading spaces +) >= fenceLen of the SAME char + trailing ws
      if (new RegExp(`^ {0,3}${fenceChar === '`' ? '`' : '~'}{${fenceLen},}\\s*$`).test(line)) {
        inFence = false;
      }
      // else fence body: backticks are literal, ignored
      continue;
    }
    // not in a fence: a 3+ run anywhere on this line opens a fence (scan only the prose before it)
    const fence = FENCE_RUN.exec(line);
    if (fence) {
      scanInline(line.slice(0, fence.index));
      fenceLen = fence[1].length;
      fenceChar = fence[1][0];
      inFence = true;
    } else {
      scanInline(line);
    }
  }
  return open !== 0;
}

/**
 * Rewrites strong/emphasis delimiter pairs whose interior has wasty whitespace into their canonical
 * form so CommonMark renders them as bold instead of literal text (Issue #6), e.g.
 * `** BUILD SUCCESS **` → `**BUILD SUCCESS**` and `** 1 (jre placement):**` → `**1 (jre placement):**`.
 * Only the interior whitespace is stripped: the lead anchor and the delimiters are preserved, so valid
 * adjacent bold spans and the spaces between them are never altered.
 *
 * <p>On top of the whitespace fix, bare {@code *} and {@code _} on code-like lines (a heredoc,
 * SQL/JSON/command echo the model reproduced verbatim) are backslash-escaped so they render
 * literally instead of pairing into an emphasis across a softbreak - e.g. {@code part_*.csv.gz}
 * and {@code COUNT(*)} on two lines of the same paragraph used to hide both stars. Only lines
 * that clearly look like code are touched: prose emphasis (a lone {@code *} between words) is
 * never rewritten.
 */
export function normalizeMarkdown(src: string): string {
  if (!src) return src;
  return escapeCodeLikeStars(src.replace(SPACED_STRONG, (_m, lead, open, _lead, inner, _trail) => `${lead}${open}${inner}${open}`));
}

// A line that looks like code the model echoed verbatim: SQL statements (keywords are matched
// uppercase-only, so English prose like "from the table" or "with him as advisor" is never
// claimed), a JSON/command payload ({"command":..., <<'...', python3...), or a `cat >` redirection.
// Lines inside a fenced block are skipped separately (their asterisks are already literal).
const CODE_LIKE_LINE =
  /(?:SELECT\b|INSERT\b|UPDATE\b|DELETE\s+FROM\b|CREATE\b|ALTER\b|DROP\b|WITH\b\s+\w+\s+AS\b|FROM\s+\w+|COUNT\s*\(|<<['"]|\{\s*"command"|\bpython3?\b|\bcat\s+>)/;

/**
 * Escapes bare {@code *} and {@code _} unescaped on code-like lines (CommonMark would otherwise
 * pair them into an emphasis, often across a softbreak of a multi-line heredoc/JSON echo). Fenced
 * code blocks are left untouched - their content is already literal. Backslash escapes already
 * present are preserved.
 */
function escapeCodeLikeStars(src: string): string {
  let inFence = false;
  let fenceChar = '';
  let fenceLen = 0;
  return src
    .split('\n')
    .map((line) => {
      if (inFence) {
        // closing fence: up to 3 leading spaces, a run of the SAME char >= opener length, trailing ws
        const m = /^ {0,3}(`+|~+)\s*$/.exec(line);
        if (m && m[1][0] === fenceChar && m[1].length >= fenceLen) {
          inFence = false;
        }
        return line;
      }
      const fence = FENCE_RUN.exec(line);
      if (fence) {
        inFence = true;
        fenceChar = fence[1][0];
        fenceLen = fence[1].length;
        return line; // the fence line itself stays untouched
      }
      if (!CODE_LIKE_LINE.test(line)) {
        return line;
      }
      // escape a bare * or _ that is not already escaped and not inside an inline code span
      let buf = '';
      let inlineCode = false;
      for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === '\\') {
          buf += c;
          if (i + 1 < line.length) {
            buf += line[i + 1];
            i++;
          }
          continue;
        }
        if (c === '`') {
          inlineCode = !inlineCode;
          buf += c;
          continue;
        }
        if (!inlineCode && (c === '*' || c === '_')) {
          buf += '\\' + c;
          continue;
        }
        buf += c;
      }
      return buf;
    })
    .join('\n');
}

// Echoed compact-seam ref tags the model may reproduce verbatim in its stream ("@N@", "@bN@",
// "@blocked@", optionally with an inline priority suffix like "@12@ [high]"). They are injected
// metadata that must never be user-visible; the server strips them from persisted messages, and
// the TUI/CLI scrub accumulated transcript text (ConversationTranscript.markdown), so the web
// render chokepoint applies the same strip on the accumulated block content. Mirrors
// io.yupiik.ia.agent.core.context.ContextCompression.ECHOED_REF_LINE / ECHOED_REF_INLINE.
const ECHOED_REF_LINE = /^[ \t]*@(?:[1-9]\d*|b[1-9]\d*|blocked)@(?:[ \t]+\[(?:low|medium|high)\])?[ \t]*\r?\n/gm;
const ECHOED_REF_INLINE = /[ \t]*@(?:[1-9]\d*|b[1-9]\d*|blocked)@(?:[ \t]+\[(?:low|medium|high)\])?/g;

// Multiline echoed seam at the end of a block: a lone fragment line ("@3149") followed only by
// empty lines and a closing lone "@" line. The frontend has no live-ref whitelist to gate it on
// (the Java render path withholds it only with that evidence), but a fragment-plus-closing-
// delimiter pair shaped exactly like a split seam is display noise: strip it like the Java
// complete-token rules strip the single-line form. Prose between the lines blocks the match.
const ECHOED_REF_MULTILINE_TAIL =
  /(^|\n)[ \t]*(@[1-9]\d*|@b[1-9]\d*|@blocked)([ \t]+\[(?:low|medium|high)\])?[ \t]*\r?\n(?:[ \t]*\r?\n)*[ \t]*@[ \t]*\r?\n?$/;

/**
 * Removes echoed compact {@code @N@} ref tags from model-generated text (assistant or thought
 * blocks). Whole echo lines first (the observed failure mode was a wall of consecutive tag lines),
 * then any inline tags that survived (e.g. a tag glued to prose on one line), then the multiline
 * split form at the end of the block. Only COMPLETE tags are stripped; a lone {@code @} is prose
 * and stays. A no-op on null/empty input.
 */
export function stripEchoedRefs(src: string | null | undefined): string {
  if (!src) return src ?? '';
  return src
    .replace(ECHOED_REF_LINE, (_m: string, lead: string) => (lead === '\n' ? '\n' : ''))
    .replace(ECHOED_REF_INLINE, '')
    .replace(ECHOED_REF_MULTILINE_TAIL, (_m: string, lead: string) => lead);
}