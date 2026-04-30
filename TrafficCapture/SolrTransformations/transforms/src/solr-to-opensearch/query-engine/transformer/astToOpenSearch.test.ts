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

describe('transformNode – LocalParamsNode handling', () => {
  it('transforms LocalParamsNode body', () => {
    const result = transformNode({
      type: 'localParams',
      params: [{ key: 'type', value: 'dismax', deref: false }],
      rawBody: 'title:java',
      body: { type: 'field', field: 'title', value: 'java' },
    });

    expect(result).toEqual(new Map([
      ['match', new Map([['title', new Map([['query', 'java']])]])],
    ]));
  });

  it('returns match_all when LocalParamsNode has no body', () => {
    const result = transformNode({
      type: 'localParams',
      params: [{ key: 'type', value: 'dismax', deref: false }],
      rawBody: null,
      body: null,
    });

    expect(result).toEqual(new Map([['match_all', new Map()]]));
  });
});

describe('transformNode – fieldTypes threading', () => {
  it('uses match for field when fieldTypes is absent (default)', () => {
    const result = transformNode({ type: 'field', field: 'status', value: 'active' });

    expect(result).toEqual(new Map([
      ['match', new Map([['status', new Map([['query', 'active']])]])],
    ]));
  });

  it('uses term for exact field when fieldTypes identifies it as non-text', () => {
    const fieldTypes = new Map([['status', 'solr.StrField']]);

    const result = transformNode({ type: 'field', field: 'status', value: 'active' }, fieldTypes);

    expect(result).toEqual(new Map([['term', new Map([['status', new Map([['value', 'active']])]])]]));
  });

  it('uses match for text field when fieldTypes identifies it as TextField', () => {
    const fieldTypes = new Map([['title', 'solr.TextField']]);

    const result = transformNode({ type: 'field', field: 'title', value: 'java' }, fieldTypes);

    expect(result).toEqual(new Map([
      ['match', new Map([['title', new Map([['query', 'java']])]])],
    ]));
  });

  it('threads fieldTypes through BoolNode to nested FieldNodes', () => {
    // a:b AND c:d where both a and c are exact fields
    const fieldTypes = new Map([
      ['a', 'solr.StrField'],
      ['c', 'solr.StrField'],
    ]);

    const result = transformNode({
      type: 'bool',
      and: [
        { type: 'field', field: 'a', value: 'b' },
        { type: 'field', field: 'c', value: 'd' },
      ],
      or: [],
      not: [],
    }, fieldTypes);

    const must = (result.get('bool') as Map<string, any>).get('must') as Map<string, any>[];
    expect(must[0]).toEqual(new Map([['term', new Map([['a', new Map([['value', 'b']])]])]]));
    expect(must[1]).toEqual(new Map([['term', new Map([['c', new Map([['value', 'd']])]])]]));
  });

  it('threads fieldTypes through GroupNode to nested FieldNode', () => {
    const fieldTypes = new Map([['id', 'solr.StrField']]);

    const result = transformNode({
      type: 'group',
      child: { type: 'field', field: 'id', value: '42' },
    }, fieldTypes);

    expect(result).toEqual(new Map([['term', new Map([['id', new Map([['value', '42']])]])]]));
  });

  it('threads fieldTypes through LocalParamsNode body', () => {
    const fieldTypes = new Map([['status', 'solr.StrField']]);

    const result = transformNode({
      type: 'localParams',
      params: [],
      rawBody: 'status:active',
      body: { type: 'field', field: 'status', value: 'active' },
    }, fieldTypes);

    expect(result).toEqual(new Map([['term', new Map([['status', new Map([['value', 'active']])]])]]));
  });
});
