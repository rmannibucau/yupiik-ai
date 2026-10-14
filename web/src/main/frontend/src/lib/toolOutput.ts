/**
 * Tool-output language detection, a TypeScript mirror of the TUI's ToolOutputLanguage.guess +
 * SyntaxHighlighter.guessLanguage. The web renders tool cards (read_file / bash / edit_file ...)
 * whose raw output is a plain <pre>; to reach the TUI's syntax coloring we guess the language cheaply
 * and best-effort so the output can be pushed through the Prism highlighter (react-syntax-highlighter).
 *
 * Signals, strongest first (mirrors the TUI):
 *   1. the command/tool that produced the output: bash's `command` (python3 / node / psql ...), or a
 *      shell wrapping (`sh -c "python3 x.py"`) - the command is already shown in the card header;
 *   2. the shebang of a printed script;
 *   3. runtime fingerprints (a python traceback, SQL statements);
 *   4. the extension of the file the output is about (read_file's `path`: Foo.java -> java);
 *   5. keyword density, when one language clearly dominates;
 *   6. nothing convincing -> null (the renderer falls back to the plain text <pre>).
 *
 * The returned ids are Prism grammar names understood by <SyntaxHighlighter language={id}> (bash,
 * markup, java, python, javascript, typescript, sql, json, yaml). Wrong guesses only cost colors,
 * never layout: it is pure detection, no reformatting.
 */

/** Programs whose output *is* in that language ({@link grammar}). A shell/cat/grep is absent on purpose. */
const PROGRAMS: Record<string, string> = {
  python: 'python',
  python2: 'python',
  python3: 'python',
  pypy: 'python',
  ipython: 'python',
  pip: 'python',
  pip3: 'python',
  pipenv: 'python',
  poetry: 'python',
  pytest: 'python',
  'py.test': 'python',
  tox: 'python',
  uv: 'python',
  conda: 'python',
  node: 'javascript',
  nodejs: 'javascript',
  npm: 'javascript',
  npx: 'javascript',
  yarn: 'javascript',
  pnpm: 'javascript',
  bun: 'javascript',
  deno: 'javascript',
  tsc: 'typescript',
  psql: 'sql',
  mysql: 'sql',
  mariadb: 'sql',
  sqlite3: 'sql',
  sqlplus: 'sql',
  javac: 'java',
  kotlinc: 'java',
  groovy: 'java',
};

/** Keyword fingerprints driving {@link fromKeywords}. */
const KEYWORDS: Record<string, string[]> = {
  python: ['def ', 'class ', 'import ', 'self.', 'print(', 'elif ', '__init__', 'lambda ', 'None', 'True'],
  javascript: ['=>', 'function ', 'const ', 'let ', 'var ', 'console.log(', '=== ', 'await ', 'export '],
  sql: ['SELECT ', 'FROM ', 'WHERE ', 'JOIN ', 'INSERT INTO ', 'GROUP BY ', 'ORDER BY ', 'CREATE TABLE '],
};

const SHELL_PROGRAMS = new Set(['sh', 'bash', 'zsh', 'dash', 'ksh']);
/** Launchers skipped when looking for the program actually doing the work. */
const WRAPPERS = new Set(['sudo', 'doas', 'env', 'time', 'nohup', 'nice', 'ionice', 'stdbuf', 'exec', 'command', 'builtin']);

const EXEC_FLAGS = new Set(['-c', '-lc', '-ic', '-lic']);
const COMMAND_SEGMENT = /(?:&&|\|\||[;|\n])/;
/** Languages a referenced file extension may contribute (Prism has a grammar for each). */
const PATH_LANGUAGES = new Set(['java', 'python', 'bash', 'sql', 'javascript', 'typescript', 'json', 'yaml', 'markup']);

const COMMAND_TOKEN = /[\s;&|()]+/;
const ASSIGNMENT = /^[A-Za-z_][A-Za-z0-9_]*=.*$/;
const QUOTES = /^['"]+|['"]+$/;
const SHEBANG = /^#!\s*(?:\/[A-Za-z0-9_./-]*\/env\s+)?(?:\S*\/)?(\S+)/;
const PYTHON_TRACEBACK = /^Traceback \(most recent call last\):/m;
const PYTHON_FRAME = /^\s*File "[^"]*\.py[w]?", line \d+/m;
const SQL_STATEMENT =
  /^(?:select|insert\s+into|update|delete\s+from|create\s+(?:table|index|view)|alter\s+table|with)\s+[\w("']/im;
const PATH_REFERENCE = /[A-Za-z0-9_./~+-]+\.[A-Za-z0-9_]{1,8}\b/g;

/** How much of the output is inspected: every signal lives in its header. */
const MAX_INSPECTED = 8192;
/** Minimum keyword hits before a language wins, and how much it must beat the runner-up. */
const MIN_KEYWORD_HITS = 3;
const KEYWORD_RATIO = 2;

/**
 * Maps a file path to a Prism grammar id, or null when the extension is unknown (callers then fall
 * back to the plain text). Mirrors the TUI's SyntaxHighlighter.guessLanguage.
 */
export function guessPathLanguage(path: string | null | undefined): string | null {
  if (!path) return null;
  let name = path;
  const slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
  if (slash >= 0) name = name.substring(slash + 1);
  const dot = name.lastIndexOf('.');
  if (dot < 0) return null;
  switch (name.substring(dot + 1).toLowerCase()) {
    case 'java': return 'java';
    case 'kt': case 'kts': case 'gradle': case 'groovy': return 'java';
    case 'xml': case 'html': case 'htm': case 'svg': case 'xhtml': case 'css': case 'scss': case 'less': return 'markup';
    case 'json': case 'jsonc': case 'json5': return 'json';
    case 'yaml': case 'yml': case 'properties': case 'ini': case 'toml': return 'yaml';
    case 'js': case 'mjs': case 'cjs': return 'javascript';
    case 'ts': case 'mts': case 'cts': return 'typescript';
    case 'tsx': return 'typescript';
    case 'py': case 'pyw': return 'python';
    case 'sh': case 'bash': case 'zsh': return 'bash';
    case 'sql': return 'sql';
    default: return null;
  }
}

/**
 * Best-effort language guess for a raw tool output, given the tool's raw arguments JSON
 * (the `args` string carried by the tool-started SSE event) so bash's `command` and read_file's
 * `path` are available. Returns a Prism grammar id or null when nothing is convincing enough.
 */
export function guessToolLanguage(output: string | null | undefined, argsJson?: string | null): string | null {
  if (!output || !output.trim()) return null;
  const head = output.length > MAX_INSPECTED ? output.substring(0, MAX_INSPECTED) : output;

  // strongest first: the command that produced the output, followed by the file the output is about.
  const command = commandFromArgs(argsJson);
  if (command) {
    const commandLang = fromCommand(command, 0);
    if (commandLang) return commandLang;
  }
  const filePath = pathFromArgs(argsJson);
  if (filePath) {
    const pathLangFromArgs = guessPathLanguage(filePath);
    if (pathLangFromArgs) return pathLangFromArgs;
  }
  const shebangLang = fromShebang(head);
  if (shebangLang) return shebangLang;
  const runtimeLang = fromRuntime(head);
  if (runtimeLang) return runtimeLang;
  const pathLang = fromPaths(head);
  if (pathLang) return pathLang;
  return fromKeywords(head);
}

/**
 * Builds a short, human-readable input summary for a tool card from its raw arguments JSON. Mirrors the
 * TUI's condensed tool line ({@code ToolCard.keyCommand} / {@code ConversationTranscript.toolCommand}): the
 * significant `command` for bash-style tools, and the `path` (+ optional `lines` range) for file tools such
 * as read_file. Returns '' (an empty string) when there is nothing meaningful to show (missing/blank/malformed
 * args or none of the known keys), in which case the card header falls back to the bare tool name.
 */
export function summarizeArgs(argsJson?: string | null): string {
  const parsed = parseArgs(argsJson);
  if (!parsed) return '';
  return String(joinArgsParts(commandPart(parsed), pathPart(parsed), workdirPart(parsed), linesPart(parsed)));
}

function commandPart(parsed: Record<string, unknown>): string {
  for (const key of ['command', 'cmd', 'script', 'args']) {
    const v = parsed[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return '';
}

function pathPart(parsed: Record<string, unknown>): string {
  for (const key of ['path', 'file', 'uri', 'target']) {
    const v = parsed[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return '';
}

function workdirPart(parsed: Record<string, unknown>): string {
  const v = parsed['workdir'];
  return typeof v === 'string' && v.trim() ? v.trim() : '';
}

function linesPart(parsed: Record<string, unknown>): string {
  const v = parsed['lines'];
  if (v == null) return '';
  if (typeof v === 'string') return v.trim() ? v.trim() : '';
  if (typeof v === 'number') return String(v);
  if (Array.isArray(v)) return v.filter((x) => typeof x === 'number').join('-');
  return '';
}

function joinArgsParts(...parts: string[]): string {
  return parts.filter((p) => p).join(' ');
}

/** Language of the program the command line actually runs, following `sh -c "..."`. */
function fromCommand(command: string, depth: number): string | null {
  if (!command || !command.trim() || depth > 2) return null;
  for (const rawSegment of command.split(COMMAND_SEGMENT)) {
    const segment = rawSegment.trim();
    if (!segment) continue;
    const tokens = tokenize(segment);
    for (let index = 0; index < tokens.length; index++) {
      const token = tokens[index];
      if (ASSIGNMENT.test(token) || WRAPPERS.has(token)) continue;
      const program = baseName(token);
      if (SHELL_PROGRAMS.has(program)) {
        const inner = innerCommand(tokens, index);
        if (inner) return fromCommand(inner, depth + 1);
        break; // a bare shell invocation: the shebang/path signals know better
      }
      const language = PROGRAMS[program];
      if (language) return language;
      break; // a plain command: this segment says nothing about the language
    }
  }
  return null;
}

/** The command a shell interpreter is asked to run, null when there is none. */
function innerCommand(tokens: string[], from: number): string | null {
  for (let flag = from + 1; flag < tokens.length; flag++) {
    if (EXEC_FLAGS.has(tokens[flag])) {
      const inner = tokens.slice(flag + 1).join(' ');
      return inner.trim() ? inner : null;
    }
  }
  return null;
}

function tokenize(command: string): string[] {
  const out: string[] = [];
  for (const raw of command.split(COMMAND_TOKEN)) {
    const token = raw.replace(QUOTES, '');
    if (token) out.push(token);
  }
  return out;
}

function baseName(program: string): string {
  const slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
  const name = slash < 0 ? program : program.substring(slash + 1);
  return name.toLowerCase();
}

/** Language of a script's interpreter when the output starts with a shebang. */
function fromShebang(output: string): string | null {
  const m = SHEBANG.exec(output);
  if (!m) return null;
  return PROGRAMS[baseName(m[1])] ?? null;
}

/** Language proven by the error shape a runtime leaves in the output. */
function fromRuntime(output: string): string | null {
  if (PYTHON_TRACEBACK.test(output) || PYTHON_FRAME.test(output)) return 'python';
  if (SQL_STATEMENT.test(output)) return 'sql';
  return null;
}

/** Language of the files the output is about, only when every recognized reference agrees. */
function fromPaths(output: string): string | null {
  let language = '';
  let hits = 0;
  let m: RegExpExecArray | null;
  PATH_REFERENCE.lastIndex = 0;
  while ((m = PATH_REFERENCE.exec(output))) {
    const candidate = guessPathLanguage(m[0]);
    if (!candidate || !PATH_LANGUAGES.has(candidate)) continue;
    if (hits === 0) language = candidate;
    else if (language !== candidate) return null;
    hits++;
  }
  return hits >= 2 ? language : null;
}

/** Language whose keywords clearly dominate the output. */
function fromKeywords(output: string): string | null {
  let language = '';
  let best = 0;
  let second = 0;
  for (const entry of Object.entries(KEYWORDS)) {
    let hits = 0;
    for (const keyword of entry[1]) hits += count(output, keyword);
    if (hits > best) {
      second = best;
      best = hits;
      language = entry[0];
    } else if (hits > second) {
      second = hits;
    }
  }
  return best >= MIN_KEYWORD_HITS && best >= KEYWORD_RATIO * second ? language : null;
}

function count(haystack: string, needle: string): number {
  let hits = 0;
  let from = 0;
  for (;;) {
    const at = haystack.indexOf(needle, from);
    if (at < 0) return hits;
    hits++;
    from = at + needle.length;
  }
}

/** Extracts the `command` property from a tool-arguments JSON string, if any. */
function commandFromArgs(argsJson?: string | null): string | null {
  const parsed = parseArgs(argsJson);
  if (!parsed) return null;
  for (const key of ['command', 'cmd', 'args', 'script']) {
    const v = parsed[key];
    if (typeof v === 'string' && v.trim()) return v;
  }
  return null;
}

/** Extracts a file path the output is about (read_file / grep path), if unambiguous. */
function pathFromArgs(argsJson?: string | null): string | null {
  const parsed = parseArgs(argsJson);
  if (!parsed) return null;
  for (const key of ['path', 'file', 'uri', 'target']) {
    const v = parsed[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  if (typeof parsed.path === 'string' && parsed.path.trim()) return parsed.path.trim();
  return null;
}

function parseArgs(argsJson?: string | null): Record<string, unknown> | null {
  if (!argsJson || !argsJson.trim() || argsJson.trim() === '{}') return null;
  try {
    const v = JSON.parse(argsJson) as unknown;
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}