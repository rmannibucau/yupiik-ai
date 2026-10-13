import { describe, expect, it } from 'vitest';
import { guessToolLanguage, guessPathLanguage } from './toolOutput';

describe('guessPathLanguage', () => {
  it('maps code file extensions to Prism grammars', () => {
    expect(guessPathLanguage('/a/b/Foo.java')).toBe('java');
    expect(guessPathLanguage('script.py')).toBe('python');
    expect(guessPathLanguage('src/App.tsx')).toBe('typescript');
    expect(guessPathLanguage('data.json')).toBe('json');
    expect(guessPathLanguage('conf.yaml')).toBe('yaml');
    expect(guessPathLanguage('index.html')).toBe('markup');
    expect(guessPathLanguage('run.sh')).toBe('bash');
    expect(guessPathLanguage('query.sql')).toBe('sql');
  });

  it('returns null for unknown extensions and bare paths', () => {
    expect(guessPathLanguage('README.txt')).toBeNull();
    expect(guessPathLanguage('noextension')).toBeNull();
    expect(guessPathLanguage(null)).toBeNull();
    expect(guessPathLanguage('')).toBeNull();
  });
});

describe('guessToolLanguage', () => {
  it('read_file output is highlighted by its path extension (java)', () => {
    const out = 'package demo;\nclass Foo {\n  void bar() {}\n}';
    expect(guessToolLanguage(out, '{"path":"src/main/java/demo/Foo.java"}')).toBe('java');
  });

  it('read_file output is highlighted by its path extension (python)', () => {
    const out = 'def hello():\n    return "hi"';
    expect(guessToolLanguage(out, '{"path":"scripts/hello.py"}')).toBe('python');
  });

  it('read_file output is highlighted by its path extension (typescript)', () => {
    const out = 'const x: number = 1;';
    expect(guessToolLanguage(out, '{"path":"src/util.ts"}')).toBe('typescript');
  });

  it('bash command drives python output', () => {
    const out = 'print("hi")\nprint("there")';
    expect(guessToolLanguage(out, '{"command":"python3 script.py"}')).toBe('python');
  });

  it('bash with a shell wrapper follows the inner command (node)', () => {
    const out = 'console.log(1);\nconsole.log(2);';
    expect(guessToolLanguage(out, '{"command":"sh -c \\"node app.js\\""}')).toBe('javascript');
  });

  it('sql statements are detected from the output itself', () => {
    const out = 'SELECT id FROM users WHERE active = 1;';
    expect(guessToolLanguage(out, '{"command":"psql -d db"}')).toBe('sql');
  });

  it('python traceback fingerprints are detected even without a path or command', () => {
    const out = 'Traceback (most recent call last):\n  File "x.py", line 3, in <module>\n    print(1/0)';
    expect(guessToolLanguage(out)).toBe('python');
  });

  it('json output on a read_file with a .json path is detected as json', () => {
    const out = '{"a": 1, "b": [2, 3]}';
    expect(guessToolLanguage(out, '{"path":"conf/app.json"}')).toBe('json');
  });

  it('plain prose with no signal stays null (falls back to the plain pre)', () => {
    const out = 'The build completed successfully in 12.4s. Nothing to configure.';
    expect(guessToolLanguage(out)).toBeNull();
  });

  it('blank output returns null', () => {
    expect(guessToolLanguage('')).toBeNull();
    expect(guessToolLanguage('   ')).toBeNull();
    expect(guessToolLanguage(null)).toBeNull();
  });
});