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


describe('transformNode – GroupNode handling', () => {
  // GroupNode is a pass-through: it unwraps and transforms its child directly.
  // These tests ensure grouping semantics are preserved via AST nesting.

  it('unwraps GroupNode and transforms its child', () => {
    const result = transformNode({
      type: 'group',
      child: { type: 'field', field: 'title', value: 'java' },
    });

    // fieldRule produces expanded form: {"match": {"field": {"query": "value"}}}
    expect(result).toEqual(new Map([
      ['match', new Map([['title', new Map([['query', 'java']])]])],
    ]));
  });

  it('preserves nested bool structure through group unwrapping', () => {
    // (A OR B) → GroupNode wrapping BoolNode
    const result = transformNode({
      type: 'group',
      child: {
        type: 'bool',
        and: [],
        or: [
          { type: 'field', field: 'title', value: 'java' },
          { type: 'field', field: 'title', value: 'python' },
        ],
        not: [],
      },
    });

    const boolMap = result.get('bool') as Map<string, any>;
    expect(boolMap).toBeDefined();
    expect(boolMap.get('should')).toHaveLength(2);
  });

  it('handles nested groups correctly', () => {
    // ((A)) → nested GroupNodes
    const result = transformNode({
      type: 'group',
      child: {
        type: 'group',
        child: { type: 'field', field: 'title', value: 'java' },
      },
    });

    // fieldRule produces expanded form: {"match": {"field": {"query": "value"}}}
    expect(result).toEqual(new Map([
      ['match', new Map([['title', new Map([['query', 'java']])]])],
    ]));
  });
});
