/**
 * Lucene Parser tests — unit tests and property-based tests.
 *
 * Property tests use fast-check to validate correctness properties
 * from the design document.
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { tokenize } from '../lexer/lexer';
import { parseLucene, type ParseResult } from '../parser/luceneParser';
import type { ASTNode, BoolNode, FieldNode, PhraseNode, RangeNode, BoostNode } from '../ast/nodes';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Tokenize and parse a Solr query string with the given default field. */
function parse(query: string, df = '_text_'): ParseResult {
  const { tokens } = tokenize(query);
  return parseLucene(tokens, df);
}

/** Recursively find all nodes of a given type in an AST. */
function findNodes(node: ASTNode, type: string): ASTNode[] {
  const results: ASTNode[] = [];
  if (node.type === type) results.push(node);
  switch (node.type) {
    case 'bool':
      for (const child of [...node.must, ...node.should, ...node.must_not]) {
        results.push(...findNodes(child, type));
      }
      break;
    case 'boost':
      results.push(...findNodes(node.child, type));
      break;
    case 'group':
      results.push(...findNodes(node.child, type));
      break;
  }
  return results;
}

// ─── Generators ───────────────────────────────────────────────────────────────

/** Alphanumeric field names that aren't keywords. */
const arbFieldName = () =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Alphanumeric values that aren't keywords. */
const arbValue = () =>
  fc.stringMatching(/^[a-z][a-z0-9]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Phrase text — no double quotes inside. */
const arbPhraseText = () =>
  fc.stringMatching(/^[a-zA-Z0-9 ]{1,20}$/).filter((s) => s.trim().length > 0);

/** Boost values — positive floats. */
const arbBoostValue = () => fc.double({ min: 0.1, max: 100.0, noNaN: true });

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('Lucene Parser', () => {
  describe('unit tests', () => {
    it('parses *:* as MatchAllNode', () => {
      const result = parse('*:*');
      expect(result.ast.type).toBe('matchAll');
      expect(result.errors).toEqual([]);
    });

    it('parses field:value as FieldNode', () => {
      const result = parse('title:java');
      expect(result.ast).toEqual({ type: 'field', field: 'title', value: 'java' });
      expect(result.errors).toEqual([]);
    });

    it('parses bare value with default field', () => {
      const result = parse('java', 'content');
      expect(result.ast).toEqual({ type: 'field', field: 'content', value: 'java' });
    });

    it('parses AND expression', () => {
      const result = parse('title:java AND author:smith');
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.must).toHaveLength(2);
      expect(bool.should).toHaveLength(0);
      expect(bool.must_not).toHaveLength(0);
      expect((bool.must[0] as FieldNode).field).toBe('title');
      expect((bool.must[1] as FieldNode).field).toBe('author');
    });

    it('parses OR expression', () => {
      const result = parse('title:java OR title:python');
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.should).toHaveLength(2);
      expect(bool.must).toHaveLength(0);
    });

    it('parses NOT expression', () => {
      const result = parse('NOT title:java');
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.must_not).toHaveLength(1);
      expect((bool.must_not[0] as FieldNode).field).toBe('title');
    });

    it('parses phrase', () => {
      const result = parse('"hello world"', 'content');
      expect(result.ast.type).toBe('phrase');
      const phrase = result.ast as PhraseNode;
      expect(phrase.text).toBe('hello world');
      expect(phrase.field).toBe('content');
    });

    it('parses field:phrase', () => {
      const result = parse('title:"hello world"');
      expect(result.ast.type).toBe('phrase');
      const phrase = result.ast as PhraseNode;
      expect(phrase.text).toBe('hello world');
      expect(phrase.field).toBe('title');
    });

    it('parses inclusive range', () => {
      const result = parse('price:[10 TO 100]');
      expect(result.ast.type).toBe('range');
      const range = result.ast as RangeNode;
      expect(range.field).toBe('price');
      expect(range.lower).toBe('10');
      expect(range.upper).toBe('100');
      expect(range.lowerInclusive).toBe(true);
      expect(range.upperInclusive).toBe(true);
    });

    it('parses exclusive range', () => {
      const result = parse('price:{10 TO 100}');
      expect(result.ast.type).toBe('range');
      const range = result.ast as RangeNode;
      expect(range.lowerInclusive).toBe(false);
      expect(range.upperInclusive).toBe(false);
    });

    it('parses grouped expression', () => {
      const result = parse('(title:java OR title:python)');
      expect(result.ast.type).toBe('group');
      const group = result.ast as { type: 'group'; child: ASTNode };
      expect(group.child.type).toBe('bool');
    });

    it('parses boost on field:value', () => {
      const result = parse('title:java^2');
      expect(result.ast.type).toBe('boost');
      const boost = result.ast as BoostNode;
      expect(boost.value).toBe(2);
      expect(boost.child.type).toBe('field');
      expect((boost.child as FieldNode).field).toBe('title');
    });

    it('parses complex query: title:java AND price:[10 TO 100]', () => {
      const result = parse('title:java AND price:[10 TO 100]');
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.must).toHaveLength(2);
      expect(bool.must[0].type).toBe('field');
      expect(bool.must[1].type).toBe('range');
    });

    it('parses implicit OR between adjacent terms', () => {
      const result = parse('java python', 'content');
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.should).toHaveLength(2);
      expect((bool.should[0] as FieldNode).value).toBe('java');
      expect((bool.should[1] as FieldNode).value).toBe('python');
    });

    it('respects operator precedence: AND binds tighter than OR', () => {
      // a OR b AND c → OR(a, AND(b, c))
      const result = parse('a OR b AND c', 'df');
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.should).toHaveLength(2);
      expect((bool.should[0] as FieldNode).value).toBe('a');
      expect(bool.should[1].type).toBe('bool');
      const inner = bool.should[1] as BoolNode;
      expect(inner.must).toHaveLength(2);
    });

    it('handles empty query as MatchAllNode', () => {
      const result = parse('');
      expect(result.ast.type).toBe('matchAll');
    });

    it('reports error for unexpected token', () => {
      const result = parse(')');
      expect(result.errors.length).toBeGreaterThanOrEqual(1);
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 5: Boolean operator mapping
  // **Validates: Requirements 2.3, 2.4, 2.5**
  describe('Property 5: Boolean operator mapping', () => {
    it('AND produces BoolNode with both sub-expressions in must', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbFieldName(),
          arbValue(),
          (f1, v1, f2, v2) => {
            const query = `${f1}:${v1} AND ${f2}:${v2}`;
            const result = parse(query);
            expect(result.ast.type).toBe('bool');
            const bool = result.ast as BoolNode;
            expect(bool.must).toHaveLength(2);
            expect(bool.should).toHaveLength(0);
            expect(bool.must_not).toHaveLength(0);
            expect((bool.must[0] as FieldNode).field).toBe(f1);
            expect((bool.must[0] as FieldNode).value).toBe(v1);
            expect((bool.must[1] as FieldNode).field).toBe(f2);
            expect((bool.must[1] as FieldNode).value).toBe(v2);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('OR produces BoolNode with both sub-expressions in should', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbFieldName(),
          arbValue(),
          (f1, v1, f2, v2) => {
            const query = `${f1}:${v1} OR ${f2}:${v2}`;
            const result = parse(query);
            expect(result.ast.type).toBe('bool');
            const bool = result.ast as BoolNode;
            expect(bool.should).toHaveLength(2);
            expect(bool.must).toHaveLength(0);
            expect(bool.must_not).toHaveLength(0);
            expect((bool.should[0] as FieldNode).field).toBe(f1);
            expect((bool.should[1] as FieldNode).field).toBe(f2);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('NOT produces BoolNode with sub-expression in must_not', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          (f, v) => {
            const query = `NOT ${f}:${v}`;
            const result = parse(query);
            expect(result.ast.type).toBe('bool');
            const bool = result.ast as BoolNode;
            expect(bool.must_not).toHaveLength(1);
            expect(bool.must).toHaveLength(0);
            expect(bool.should).toHaveLength(0);
            expect((bool.must_not[0] as FieldNode).field).toBe(f);
            expect((bool.must_not[0] as FieldNode).value).toBe(v);
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 6: Default field assignment
  // **Validates: Requirements 2.2**
  describe('Property 6: Default field assignment', () => {
    it('bare values get FieldNode with field equal to df', () => {
      fc.assert(
        fc.property(
          arbValue(),
          arbFieldName(),
          (value, df) => {
            const result = parse(value, df);
            expect(result.ast.type).toBe('field');
            const field = result.ast as FieldNode;
            expect(field.field).toBe(df);
            expect(field.value).toBe(value);
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 7: Field, phrase, and range node production
  // **Validates: Requirements 2.1, 2.6, 2.7**
  describe('Property 7: Field, phrase, and range node production', () => {
    it('field:value produces FieldNode with correct field and value', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          (field, value) => {
            const result = parse(`${field}:${value}`);
            expect(result.ast.type).toBe('field');
            const node = result.ast as FieldNode;
            expect(node.field).toBe(field);
            expect(node.value).toBe(value);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('quoted text produces PhraseNode', () => {
      fc.assert(
        fc.property(
          arbPhraseText(),
          arbFieldName(),
          (text, df) => {
            const result = parse(`"${text}"`, df);
            expect(result.ast.type).toBe('phrase');
            const node = result.ast as PhraseNode;
            expect(node.text).toBe(text);
            expect(node.field).toBe(df);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('field:"phrase" produces PhraseNode with correct field', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbPhraseText(),
          (field, text) => {
            const result = parse(`${field}:"${text}"`);
            expect(result.ast.type).toBe('phrase');
            const node = result.ast as PhraseNode;
            expect(node.text).toBe(text);
            expect(node.field).toBe(field);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('range expressions produce RangeNode with correct bounds and inclusivity', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbValue(),
          fc.boolean(),
          (field, lower, upper, inclusive) => {
            const open = inclusive ? '[' : '{';
            const close = inclusive ? ']' : '}';
            const query = `${field}:${open}${lower} TO ${upper}${close}`;
            const result = parse(query);
            expect(result.ast.type).toBe('range');
            const node = result.ast as RangeNode;
            expect(node.field).toBe(field);
            expect(node.lower).toBe(lower);
            expect(node.upper).toBe(upper);
            expect(node.lowerInclusive).toBe(inclusive);
            expect(node.upperInclusive).toBe(inclusive);
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 8: Boost wrapping
  // **Validates: Requirements 2.9**
  describe('Property 8: Boost wrapping', () => {
    it('expr^N produces BoostNode wrapping the expression with boost value N', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          fc.integer({ min: 1, max: 100 }),
          (field, value, boost) => {
            const query = `${field}:${value}^${boost}`;
            const result = parse(query);
            expect(result.ast.type).toBe('boost');
            const node = result.ast as BoostNode;
            expect(node.value).toBe(boost);
            expect(node.child.type).toBe('field');
            expect((node.child as FieldNode).field).toBe(field);
            expect((node.child as FieldNode).value).toBe(value);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('phrase^N produces BoostNode wrapping PhraseNode', () => {
      fc.assert(
        fc.property(
          arbPhraseText(),
          fc.integer({ min: 1, max: 50 }),
          (text, boost) => {
            const query = `"${text}"^${boost}`;
            const result = parse(query, 'df');
            expect(result.ast.type).toBe('boost');
            const node = result.ast as BoostNode;
            expect(node.value).toBe(boost);
            expect(node.child.type).toBe('phrase');
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 9: Parser error on invalid token sequences
  // **Validates: Requirements 2.11, 9.2**
  describe('Property 9: Parser error on invalid token sequences', () => {
    it('produces errors for invalid token sequences', () => {
      fc.assert(
        fc.property(
          fc.constantFrom(
            ')',           // unexpected RPAREN
            'AND',         // leading AND
            'OR',          // leading OR
            'AND OR',      // consecutive operators
            ':value',      // leading COLON
            'field:',      // trailing COLON with no value
          ),
          (query) => {
            const { tokens } = tokenize(query);
            const result = parseLucene(tokens, '_text_');
            expect(result.errors.length).toBeGreaterThanOrEqual(1);
            // Each error should have token info and position
            for (const error of result.errors) {
              expect(error.message.length).toBeGreaterThan(0);
              expect(error.token).toBeDefined();
              expect(typeof error.position).toBe('number');
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 19: Default operator is OR
  // **Validates: Requirements 13.2**
  describe('Property 19: Default operator is OR', () => {
    it('adjacent bare terms are joined with should (OR)', () => {
      fc.assert(
        fc.property(
          arbValue(),
          arbValue(),
          arbFieldName(),
          (v1, v2, df) => {
            const query = `${v1} ${v2}`;
            const result = parse(query, df);
            expect(result.ast.type).toBe('bool');
            const bool = result.ast as BoolNode;
            expect(bool.should).toHaveLength(2);
            expect(bool.must).toHaveLength(0);
            expect(bool.must_not).toHaveLength(0);
            // Both should be FieldNodes with the default field
            expect((bool.should[0] as FieldNode).field).toBe(df);
            expect((bool.should[0] as FieldNode).value).toBe(v1);
            expect((bool.should[1] as FieldNode).field).toBe(df);
            expect((bool.should[1] as FieldNode).value).toBe(v2);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
