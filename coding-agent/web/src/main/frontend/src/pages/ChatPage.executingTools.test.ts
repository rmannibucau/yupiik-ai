import { describe, expect, it } from 'vitest';
import { isExecutingToolsCommand } from './ChatPage';

describe('isExecutingToolsCommand', () => {
  it('recognises /executing-tools and /executing-tools with trailing args', () => {
    expect(isExecutingToolsCommand('/executing-tools')).toBe(true);
    expect(isExecutingToolsCommand('/executing-tools ')).toBe(true);
    expect(isExecutingToolsCommand('  /executing-tools')).toBe(true);
  });

  it('rejects other slash commands, partial matches and plain prompts', () => {
    expect(isExecutingToolsCommand('/help')).toBe(false);
    expect(isExecutingToolsCommand('/tools')).toBe(false);
    expect(isExecutingToolsCommand('/executing')).toBe(false);
    expect(isExecutingToolsCommand('/executing-toolsX')).toBe(false);
    expect(isExecutingToolsCommand('executing-tools')).toBe(false);
    expect(isExecutingToolsCommand('')).toBe(false);
  });
});