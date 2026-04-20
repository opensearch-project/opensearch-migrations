import { describe, it, expect } from 'vitest';
import { filterRule } from './filterRule';
import { transformNode } from '../astToOpenSearch';
import type { ASTNode } from '../../ast/nodes';

/** Helper to convert nested Maps to plain objects for easier assertion. */
function mapToObject(map: Map<string, any>): any {
  const obj: any = {};
  for (const [key, value] of map.entries()) {
    if (value instanceof Map) {
      obj[key] = mapToObject(value);
    } else if (Array.isArray(value)) {
      obj[key] = value.map((v) => (v instanceof Map ? mapToObject(v) : v));
    } else {
      obj[key] = value;
    }
  }
  return obj;
}

describe('filterRule', () => {
  it('wraps simple field query in bool.filter', () => {
    const node: ASTNode = {
      type: 'filter',
      child: { type: 'field', field: 'inStock', value: 'true' },
    };

    const result = filterRule(node, transformNode);
    const obj = mapToObject(result);

    expect(obj).toEqual({
      bool: {
        filter: [
          { match: { inStock: { query: 'true' } } },
        ],
      },
    });
  });

  it('wraps match_all in bool.filter', () => {
    const node: ASTNode = {
      type: 'filter',
      child: { type: 'matchAll' },
    };

    const result = filterRule(node, transformNode);
    const obj = mapToObject(result);

    expect(obj).toEqual({
      bool: {
        filter: [{ match_all: {} }],
      },
    });
  });

  it('wraps complex bool query in bool.filter', () => {
    const node: ASTNode = {
      type: 'filter',
      child: {
        type: 'bool',
        and: [
          { type: 'field', field: 'status', value: 'active' },
          { type: 'field', field: 'type', value: 'book' },
        ],
        or: [],
        not: [],
      },
    };

    const result = filterRule(node, transformNode);
    const obj = mapToObject(result);

    expect(obj).toEqual({
      bool: {
        filter: [
          {
            bool: {
              must: [
                { match: { status: { query: 'active' } } },
                { match: { type: { query: 'book' } } },
              ],
            },
          },
        ],
      },
    });
  });

  it('avoids double-wrapping when child is already a filter-only bool query', () => {
    // Simulates nested filter(filter(...)) - the inner filter already produces
    // { bool: { filter: [...] } }, so we should not wrap it again
    const node: ASTNode = {
      type: 'filter',
      child: {
        type: 'filter',
        child: { type: 'field', field: 'inStock', value: 'true' },
      },
    };

    const result = filterRule(node, transformNode);
    const obj = mapToObject(result);

    // Should NOT produce { bool: { filter: [{ bool: { filter: [...] } }] } }
    // Instead should flatten to { bool: { filter: [...] } }
    expect(obj).toEqual({
      bool: {
        filter: [
          { match: { inStock: { query: 'true' } } },
        ],
      },
    });
  });

  it('still wraps when child bool has more than just filter clause', () => {
    // If child bool has must/should/must_not in addition to filter,
    // we should still wrap it to preserve the semantics
    const node: ASTNode = {
      type: 'filter',
      child: {
        type: 'bool',
        and: [{ type: 'field', field: 'status', value: 'active' }],
        or: [],
        not: [],
      },
    };

    const result = filterRule(node, transformNode);
    const obj = mapToObject(result);

    // Child bool has 'must', so it should be wrapped
    expect(obj).toEqual({
      bool: {
        filter: [
          {
            bool: {
              must: [{ match: { status: { query: 'active' } } }],
            },
          },
        ],
      },
    });
  });

  describe('negation inside filter', () => {
    it('wraps NOT expression in bool.filter', () => {
      const node: ASTNode = {
        type: 'filter',
        child: {
          type: 'bool',
          and: [],
          or: [],
          not: [{ type: 'field', field: 'status', value: 'inactive' }],
        },
      };

      const result = filterRule(node, transformNode);
      const obj = mapToObject(result);

      expect(obj).toEqual({
        bool: {
          filter: [
            {
              bool: {
                must_not: [{ match: { status: { query: 'inactive' } } }],
              },
            },
          ],
        },
      });
    });

    it('wraps combined AND and NOT in bool.filter', () => {
      const node: ASTNode = {
        type: 'filter',
        child: {
          type: 'bool',
          and: [{ type: 'field', field: 'category', value: 'electronics' }],
          or: [],
          not: [{ type: 'field', field: 'status', value: 'inactive' }],
        },
      };

      const result = filterRule(node, transformNode);
      const obj = mapToObject(result);

      expect(obj).toEqual({
        bool: {
          filter: [
            {
              bool: {
                must: [{ match: { category: { query: 'electronics' } } }],
                must_not: [{ match: { status: { query: 'inactive' } } }],
              },
            },
          ],
        },
      });
    });
  });

  describe('boost with filter', () => {
    it('wraps boosted field in bool.filter', () => {
      const node: ASTNode = {
        type: 'filter',
        child: {
          type: 'boost',
          child: { type: 'field', field: 'category', value: 'software' },
          value: 2,
        },
      };

      const result = filterRule(node, transformNode);
      const obj = mapToObject(result);

      // boostRule adds boost parameter to the match query
      expect(obj).toEqual({
        bool: {
          filter: [
            { match: { category: { query: 'software', boost: 2 } } },
          ],
        },
      });
    });

    it('wraps boosted bool expression in bool.filter', () => {
      const node: ASTNode = {
        type: 'filter',
        child: {
          type: 'boost',
          child: {
            type: 'group',
            child: {
              type: 'bool',
              and: [
                { type: 'field', field: 'a', value: '1' },
                { type: 'field', field: 'b', value: '2' },
              ],
              or: [],
              not: [],
            },
          },
          value: 1.5,
        },
      };

      const result = filterRule(node, transformNode);
      const obj = mapToObject(result);

      // boostRule adds boost parameter to the bool query
      expect(obj).toEqual({
        bool: {
          filter: [
            {
              bool: {
                must: [
                  { match: { a: { query: '1' } } },
                  { match: { b: { query: '2' } } },
                ],
                boost: 1.5,
              },
            },
          ],
        },
      });
    });
  });

  describe('deeply nested filters', () => {
    it('flattens triple-nested filter(filter(filter(...)))', () => {
      const node: ASTNode = {
        type: 'filter',
        child: {
          type: 'filter',
          child: {
            type: 'filter',
            child: { type: 'field', field: 'status', value: 'active' },
          },
        },
      };

      const result = filterRule(node, transformNode);
      const obj = mapToObject(result);

      // Should flatten all the way down
      expect(obj).toEqual({
        bool: {
          filter: [
            { match: { status: { query: 'active' } } },
          ],
        },
      });
    });

    it('handles nested filter with boolean inside', () => {
      const node: ASTNode = {
        type: 'filter',
        child: {
          type: 'filter',
          child: {
            type: 'bool',
            and: [
              { type: 'field', field: 'a', value: '1' },
              { type: 'field', field: 'b', value: '2' },
            ],
            or: [],
            not: [],
          },
        },
      };

      const result = filterRule(node, transformNode);
      const obj = mapToObject(result);

      // Inner filter wraps bool in filter, outer filter should not double-wrap
      expect(obj).toEqual({
        bool: {
          filter: [
            {
              bool: {
                must: [
                  { match: { a: { query: '1' } } },
                  { match: { b: { query: '2' } } },
                ],
              },
            },
          ],
        },
      });
    });
  });
});
