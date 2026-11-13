import React from 'react';
import { makePrismLightSyntaxHighlighter } from '@assistant-ui/react-syntax-highlighter';
import { PrismLight as SyntaxHighlighter } from 'react-syntax-highlighter';
import java from 'react-syntax-highlighter/dist/esm/languages/prism/java';
import typescript from 'react-syntax-highlighter/dist/esm/languages/prism/typescript';
import javascript from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import python from 'react-syntax-highlighter/dist/esm/languages/prism/python';
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import markup from 'react-syntax-highlighter/dist/esm/languages/prism/markup';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import yaml from 'react-syntax-highlighter/dist/esm/languages/prism/yaml';
import asciidoc from 'react-syntax-highlighter/dist/esm/languages/prism/asciidoc';
import markdown from 'react-syntax-highlighter/dist/esm/languages/prism/markdown';

const languages = new Map([
  ['java', 'java'],
  ['typescript', 'typescript'], ['ts', 'typescript'],
  ['javascript', 'javascript'], ['js', 'javascript'],
  ['python', 'python'], ['py', 'python'],
  ['bash', 'bash'], ['sh', 'bash'], ['shell', 'bash'],
  ['sql', 'sql'],
  ['markup', 'markup'], ['xml', 'markup'], ['html', 'markup'], ['svg', 'markup'],
  ['json', 'json'], ['yaml', 'yaml'],
  ['asciidoc', 'asciidoc'], ['adoc', 'asciidoc'],
  ['markdown', 'markdown'], ['md', 'markdown'],
]);

SyntaxHighlighter.registerLanguage('java', java);
SyntaxHighlighter.registerLanguage('typescript', typescript);
SyntaxHighlighter.registerLanguage('ts', typescript);
SyntaxHighlighter.registerLanguage('javascript', javascript);
SyntaxHighlighter.registerLanguage('js', javascript);
SyntaxHighlighter.registerLanguage('python', python);
SyntaxHighlighter.registerLanguage('py', python);
SyntaxHighlighter.registerLanguage('bash', bash);
SyntaxHighlighter.registerLanguage('sh', bash);
SyntaxHighlighter.registerLanguage('shell', bash);
SyntaxHighlighter.registerLanguage('sql', sql);
SyntaxHighlighter.registerLanguage('xml', markup);
SyntaxHighlighter.registerLanguage('html', markup);
SyntaxHighlighter.registerLanguage('svg', markup);
SyntaxHighlighter.registerLanguage('json', json);
SyntaxHighlighter.registerLanguage('yaml', yaml);
SyntaxHighlighter.registerLanguage('asciidoc', asciidoc);
SyntaxHighlighter.registerLanguage('adoc', asciidoc);
SyntaxHighlighter.registerLanguage('markdown', markdown);
SyntaxHighlighter.registerLanguage('md', markdown);

// The assistant-ui factory delegates tokenization to the registered PrismLight
// instance and lets callers supply the actual pre/code elements (no nested pre).
const Highlight = makePrismLightSyntaxHighlighter({ useInlineStyles: false });

export function CodeBlock({ code, language, className }: {
  code: string;
  language?: string;
  className?: string;
}) {
  const preClassName = ['yca-code-block', className].filter(Boolean).join(' ');
  const grammar = language === undefined ? undefined : languages.get(language);
  const codeClassName = language ? `language-${language}` : undefined;
  if (!grammar) {
    return <pre className={preClassName}><code className={codeClassName}>{code}</code></pre>;
  }
  return (
    <Highlight
      language={grammar}
      code={code}
      components={{
        Pre: ({ node, className: ignoredClassName, ...props }) => <pre {...props} className={preClassName} />,
        Code: ({ node, className: ignoredClassName, ...props }) => <code {...props} className={codeClassName} />,
      }}
    />
  );
}
