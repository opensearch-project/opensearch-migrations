import { describe, it, expect } from 'vitest';
import { transformNode } from './astToOpenSearch';
import type { BoolNode } from '../ast/nodes';

describe('transformNode', () => {
  it('recursively transforms nested registered nodes', () => {
    const inner: BoolNode = { type: 'bool', and: [], or: [], not: [] };
    const outer: BoolNode = { type: 'bool', and: [inner], or: [], not: [] };
    const result = transformNode(outer);

    const mustArray = (result.get('bool') as Map<string, any>).get('must') as Map<string, any>[];
    expect(mustArray).toHaveLength(1);
    expect(mustArray[0].get('bool')).toBeDefined();
  });
});
