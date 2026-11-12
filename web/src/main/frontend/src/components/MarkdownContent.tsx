import React from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Element } from 'hast';
import { remarkCallouts } from '../lib/remarkCallouts';
import { CodeBlock } from './CodeBlock';

const LANGUAGE_CLASS = /(?:^|\s)language-(\S+)(?=\s|$)/;

function rawText(node: Element): string {
  return node.children.map(child => {
    if (child.type === 'text') {
      return child.value;
    }
    if (child.type === 'element') {
      return rawText(child);
    }
    return '';
  }).join('');
}

const components: Components = {
  a: ({ node, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer" />,
  pre: ({ node, children, className, ...props }) => {
    const code = node?.children.find(child => child.type === 'element' && child.tagName === 'code');
    if (code?.type !== 'element') {
      return <pre {...props} className={className}>{children}</pre>;
    }
    const classes = code.properties.className;
    const language = LANGUAGE_CLASS.exec(Array.isArray(classes) ? classes.join(' ') : String(classes ?? ''))?.[1];
    return <CodeBlock code={rawText(code)} language={language} className={className} />;
  },
};

export function MarkdownContent({ content, variant = 'document' }: {
  content: string;
  variant?: 'user' | 'assistant' | 'document' | 'thought';
}) {
  return (
    <div className="aui-md" data-variant={variant}>
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkCallouts]} components={components}>
        {content}
      </ReactMarkdown>
    </div>
  );
}
