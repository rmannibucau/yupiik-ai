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

/**
 * Rewrites strong/emphasis delimiter pairs whose interior has wasty whitespace into their canonical
 * form so CommonMark renders them as bold instead of literal text (Issue #6), e.g.
 * `** BUILD SUCCESS **` → `**BUILD SUCCESS**` and `** 1 (jre placement):**` → `**1 (jre placement):**`.
 * Only the interior whitespace is stripped: the lead anchor and the delimiters are preserved, so valid
 * adjacent bold spans and the spaces between them are never altered.
 */
export function normalizeMarkdown(src: string): string {
  if (!src) return src;
  return src.replace(SPACED_STRONG, (_m, lead, open, _lead, inner, _trail) => `${lead}${open}${inner}${open}`);
}