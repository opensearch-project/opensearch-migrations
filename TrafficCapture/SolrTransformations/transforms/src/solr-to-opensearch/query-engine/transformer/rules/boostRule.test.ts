import { describe, it, expect } from 'vitest';
import { transformNode } from '../astToOpenSearch';
import { boostRule } from './boostRule';
import type { ASTNode, BoostNode, FieldNode, PhraseNode, RangeNode, BareNode, BoolNode, MatchAllNode, GroupNode } from '../../ast/nodes';
import type { TransformChild } from '../types';

/** Helper to create Map<string, any> with mixed value types */
const m = (entries: [string, any][]): Map<string, any> => new Map(entries);

describe('boostRule', () => {
  describe('field-level boost (match, match_phrase, range)', () => {
    it('adds boost to match query', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: { type: 'field', field: 'title', value: 'java' } as FieldNode,
      };

      const result = transformNode(node);

      // fieldRule produces expanded form: {"match": {"title": {"query": "java"}}}
      // boostRule adds boost inside the field object
      expect(result).toEqual(m([['match', m([['title', m([['query', 'java'], ['boost', 2]])]])]]));
    });

    it('adds boost to match_phrase query', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 1.5,
        child: { type: 'phrase', field: 'title', text: 'hello world' } as PhraseNode,
      };

      const result = transformNode(node);

      expect(result).toEqual(m([['match_phrase', m([['title', m([['query', 'hello world'], ['boost', 1.5]])]])]]));
    });

    it('adds boost to range query', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: {
          type: 'range',
          field: 'price',
          lower: '10',
          upper: '100',
          lowerInclusive: true,
          upperInclusive: true,
        } as RangeNode,
      };

      const result = transformNode(node);

      expect(result).toEqual(m([['range', m([['price', m([['gte', '10'], ['lte', '100'], ['boost', 2]])]])]]));
    });
  });

  describe('query-level boost (query_string, bool, exists, match_all)', () => {
    it('adds boost to query_string (bare term)', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: { type: 'bare', value: 'java', isPhrase: false } as BareNode,
      };

      const result = transformNode(node);

      expect(result).toEqual(m([['query_string', m([['query', 'java'], ['boost', 2]])]]));
    });

    it('adds boost to bool query', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: {
          type: 'bool',
          and: [{ type: 'field', field: 'title', value: 'java' } as FieldNode],
          or: [],
          not: [],
        } as BoolNode,
      };

      const result = transformNode(node);
      const boolMap = result.get('bool') as Map<string, any>;

      expect(boolMap.get('boost')).toBe(2);
      expect(boolMap.has('must')).toBe(true);
    });

    it('adds boost to exists query (field:*)', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: { type: 'field', field: 'title', value: '*' } as FieldNode,
      };

      const result = transformNode(node);

      expect(result).toEqual(m([['exists', m([['field', 'title'], ['boost', 2]])]]));
    });

    it('adds boost to match_all query', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: { type: 'matchAll' } as MatchAllNode,
      };

      const result = transformNode(node);

      expect(result).toEqual(m([['match_all', m([['boost', 2]])]]));
    });
  });

  describe('edge cases', () => {
    it('handles decimal boost values', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 0.5,
        child: { type: 'field', field: 'title', value: 'java' } as FieldNode,
      };

      const result = transformNode(node);
      const matchMap = result.get('match') as Map<string, any>;
      const fieldMap = matchMap.get('title') as Map<string, any>;

      expect(fieldMap.get('boost')).toBe(0.5);
    });

    it('preserves default_field when boosting bare query', () => {
      const node: BoostNode = {
        type: 'boost',
        value: 2,
        child: { type: 'bare', value: 'java', isPhrase: false, defaultField: 'content' } as BareNode,
      };

      const result = transformNode(node);

      expect(result).toEqual(m([['query_string', m([['query', 'java'], ['default_field', 'content'], ['boost', 2]])]]));
    });

    it('calls transformChild exactly once', () => {
      const calls: ASTNode[] = [];
      const trackingTransformChild: TransformChild = (child) => {
        calls.push(child);
        // Return expanded form that fieldRule now produces
        return m([['match', m([['title', m([['query', 'java']])]])]]);
      };

      const childNode: FieldNode = { type: 'field', field: 'title', value: 'java' };
      const node: BoostNode = { type: 'boost', value: 2, child: childNode };

      boostRule(node, trackingTransformChild);

      expect(calls).toHaveLength(1);
      expect(calls[0]).toBe(childNode);
    });

    it('throws when called with wrong node type', () => {
      const stubTransformChild: TransformChild = () => m([]);
      const wrongNode: FieldNode = { type: 'field', field: 'title', value: 'java' };

      expect(() => boostRule(wrongNode, stubTransformChild)).toThrow(
        '[boostRule] Called with wrong node type: field',
      );
    });

    it('handles nested boost (boost wrapping boosted term)', () => {
      // Represents ((title:java^2)^3) - outer boost wrapping inner boosted term
      const node: BoostNode = {
        type: 'boost',
        value: 3,
        child: {
          type: 'boost',
          value: 2,
          child: { type: 'field', field: 'title', value: 'java' } as FieldNode,
        } as BoostNode,
      };

      const result = transformNode(node);

      // Inner boost produces: match.title.boost = 2
      // Outer boost overwrites: match.title.boost = 3
      const matchMap = result.get('match') as Map<string, any>;
      const fieldMap = matchMap.get('title') as Map<string, any>;
      expect(fieldMap.get('boost')).toBe(3); // Outer boost overwrites inner
    });

    it('handles boost on group containing boosted terms', () => {
      // Represents (title:java^2 OR author:smith^1)^3
      const node: BoostNode = {
        type: 'boost',
        value: 3,
        child: {
          type: 'group',
          child: {
            type: 'bool',
            and: [],
            or: [
              { type: 'boost', value: 2, child: { type: 'field', field: 'title', value: 'java' } as FieldNode } as BoostNode,
              { type: 'boost', value: 1, child: { type: 'field', field: 'author', value: 'smith' } as FieldNode } as BoostNode,
            ],
            not: [],
          } as BoolNode,
        } as GroupNode,
      };

      const result = transformNode(node);

      // Outer boost goes on the bool query
      const boolMap = result.get('bool') as Map<string, any>;
      expect(boolMap.get('boost')).toBe(3);

      // Inner boosts are preserved on individual match queries (inside field object)
      const shouldClauses = boolMap.get('should') as Map<string, any>[];
      const titleMatch = shouldClauses[0].get('match') as Map<string, any>;
      const authorMatch = shouldClauses[1].get('match') as Map<string, any>;
      expect(titleMatch.get('title').get('boost')).toBe(2);
      expect(authorMatch.get('author').get('boost')).toBe(1);
    });

    it('throws when child returns non-Map result', () => {
      const stubTransformChild: TransformChild = () => null as any;

      const node: BoostNode = { type: 'boost', value: 2, child: { type: 'matchAll' } as MatchAllNode };

      expect(() => boostRule(node, stubTransformChild)).toThrow(
        '[boostRule] Child transformer returned invalid result: expected non-empty Map',
      );
    });

    it('throws when child returns empty Map', () => {
      const stubTransformChild: TransformChild = () => m([]);

      const node: BoostNode = { type: 'boost', value: 2, child: { type: 'matchAll' } as MatchAllNode };

      expect(() => boostRule(node, stubTransformChild)).toThrow(
        '[boostRule] Child transformer returned invalid result: expected non-empty Map',
      );
    });

    it('throws when query body is not a Map', () => {
      const stubTransformChild: TransformChild = () => m([['custom_query', 'not a map']]);

      const node: BoostNode = { type: 'boost', value: 2, child: { type: 'matchAll' } as MatchAllNode };

      expect(() => boostRule(node, stubTransformChild)).toThrow(
        "[boostRule] Query body for type 'custom_query' is not a Map",
      );
    });

    it('handles child returning empty query body (falls back to query-level boost)', () => {
      // Edge case: child transformer returns a query with empty body
      // This shouldn't happen in practice but tests defensive behavior
      const stubTransformChild: TransformChild = () => m([['custom_query', m([])]]);

      const node: BoostNode = { type: 'boost', value: 2, child: { type: 'matchAll' } as MatchAllNode };

      const result = boostRule(node, stubTransformChild);

      // With empty body, firstValue is undefined (not a Map), so boost goes at query level
      expect(result.get('custom_query').get('boost')).toBe(2);
    });

    it('handles child returning unexpected structure with array value', () => {
      // Edge case: child returns a query where first value is an array (not Map or primitive)
      // This tests the else branch where firstValue is not instanceof Map
      const stubTransformChild: TransformChild = () => m([['ids', m([['values', ['id1', 'id2']]])]]);

      const node: BoostNode = { type: 'boost', value: 2, child: { type: 'matchAll' } as MatchAllNode };

      const result = boostRule(node, stubTransformChild);

      // Array is not a Map, so boost goes at query body level
      const idsBody = result.get('ids') as Map<string, any>;
      expect(idsBody.get('boost')).toBe(2);
      expect(idsBody.get('values')).toEqual(['id1', 'id2']);
    });
  });
});
