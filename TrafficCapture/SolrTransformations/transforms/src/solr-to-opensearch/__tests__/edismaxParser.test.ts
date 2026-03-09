/**
 * eDisMax Parser tests — unit tests and property-based tests.
 *
 * Property tests use fast-check to validate correctness properties
 * from the design document.
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { tokenize } from '../lexer/lexer';
import { parseEdismax, parseFieldList, type EDisMaxConfig } from '../parser/edismaxParser';
import { parseLucene, type ParseResult } from '../parser/luceneParser';
import type { ASTNode, BoolNode, FieldNode, BoostNode, PhraseNode } from '../ast/nodes';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Tokenize and parse an eDisMax query with the given config. */
function parseEdismaxQuery(query: string, config: EDisMaxConfig): ParseResult {
  const { tokens } = tokenize(query);
  return parseEdismax(tokens, config);
}

/** Tokenize and parse a Lucene query with the given default field. */
function parseLuceneQuery(query: string, df = '_text_'): ParseResult {
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

/**
 * Deep-compare two AST nodes for structural equivalence.
 * Ignores node identity — only compares shape and values.
 */
function astEqual(a: ASTNode, b: ASTNode): boolean {
  if (a.type !== b.type) return false;
  switch (a.type) {
    case 'field':
      return a.field === (b as FieldNode).field && a.value === (b as FieldNode).value;
    case 'phrase':
      return a.text === (b as PhraseNode).text && a.field === (b as PhraseNode).field;
    case 'matchAll':
      return true;
    case 'range':
      return (
        a.field === (b as typeof a).field &&
        a.lower === (b as typeof a).lower &&
        a.upper === (b as typeof a).upper &&
        a.lowerInclusive === (b as typeof a).lowerInclusive &&
        a.upperInclusive === (b as typeof a).upperInclusive
      );
    case 'boost':
      return a.value === (b as BoostNode).value && astEqual(a.child, (b as BoostNode).child);
    case 'group':
      return astEqual(a.child, (b as typeof a).child);
    case 'bool': {
      const bb = b as BoolNode;
      return (
        a.must.length === bb.must.length &&
        a.should.length === bb.should.length &&
        a.must_not.length === bb.must_not.length &&
        a.must.every((n, i) => astEqual(n, bb.must[i])) &&
        a.should.every((n, i) => astEqual(n, bb.should[i])) &&
        a.must_not.every((n, i) => astEqual(n, bb.must_not[i]))
      );
    }
  }
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

/** Boost values — positive integers for simplicity. */
const arbBoostInt = () => fc.integer({ min: 1, max: 10 });

/** Generate a list of 1-4 weighted fields for qf. */
const arbQfFields = () =>
  fc.array(
    fc.tuple(arbFieldName(), fc.option(arbBoostInt(), { nil: undefined })),
    { minLength: 1, maxLength: 4 },
  ).map((pairs) =>
    pairs.map(([field, boost]) => (boost !== undefined ? `${field}^${boost}` : field)).join(' '),
  );

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('eDisMax Parser', () => {
  describe('parseFieldList', () => {
    it('parses fields with boosts', () => {
      const result = parseFieldList('title^2 content');
      expect(result).toEqual([
        { field: 'title', boost: 2 },
        { field: 'content', boost: 1.0 },
      ]);
    });

    it('parses fields without boosts', () => {
      const result = parseFieldList('title content body');
      expect(result).toEqual([
        { field: 'title', boost: 1.0 },
        { field: 'content', boost: 1.0 },
        { field: 'body', boost: 1.0 },
      ]);
    });

    it('handles empty string', () => {
      const result = parseFieldList('');
      expect(result).toEqual([]);
    });

    it('handles float boosts', () => {
      const result = parseFieldList('title^2.5 content^0.5');
      expect(result).toEqual([
        { field: 'title', boost: 2.5 },
        { field: 'content', boost: 0.5 },
      ]);
    });
  });

  describe('unit tests', () => {
    it('distributes unfielded term across qf fields', () => {
      const result = parseEdismaxQuery('java', { qf: 'title^2 content' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.should).toHaveLength(2);
      // title^2 → BoostNode wrapping FieldNode
      expect(bool.should[0].type).toBe('boost');
      const boosted = bool.should[0] as BoostNode;
      expect(boosted.value).toBe(2);
      expect((boosted.child as FieldNode).field).toBe('title');
      expect((boosted.child as FieldNode).value).toBe('java');
      // content (boost 1.0) → plain FieldNode
      expect(bool.should[1].type).toBe('field');
      expect((bool.should[1] as FieldNode).field).toBe('content');
      expect((bool.should[1] as FieldNode).value).toBe('java');
    });

    it('preserves explicitly fielded terms', () => {
      const result = parseEdismaxQuery('title:java', { qf: 'title^2 content' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('field');
      const field = result.ast as FieldNode;
      expect(field.field).toBe('title');
      expect(field.value).toBe('java');
    });

    it('handles mixed fielded and unfielded terms', () => {
      const result = parseEdismaxQuery('title:java python', { qf: 'title^2 content' });
      expect(result.errors).toEqual([]);
      // Should be OR of title:java and (distributed python)
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.should.length).toBeGreaterThanOrEqual(2);
    });

    it('handles single qf field with boost', () => {
      const result = parseEdismaxQuery('java', { qf: 'title^3' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('boost');
      const boost = result.ast as BoostNode;
      expect(boost.value).toBe(3);
      expect((boost.child as FieldNode).field).toBe('title');
    });

    it('handles single qf field without boost', () => {
      const result = parseEdismaxQuery('java', { qf: 'title' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('field');
      expect((result.ast as FieldNode).field).toBe('title');
    });

    it('supports AND between terms with qf distribution', () => {
      const result = parseEdismaxQuery('java AND python', { qf: 'title content' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.must).toHaveLength(2);
      // Each must clause should be a BoolNode(should) distributing across qf
      for (const clause of bool.must) {
        expect(clause.type).toBe('bool');
        expect((clause as BoolNode).should).toHaveLength(2);
      }
    });

    it('appends phrase boost nodes when pf is specified', () => {
      const result = parseEdismaxQuery('java python', {
        qf: 'title content',
        pf: 'title^3',
      });
      expect(result.errors).toEqual([]);
      // Should contain phrase boost nodes in should
      const allPhrases = findNodes(result.ast, 'phrase');
      expect(allPhrases.length).toBeGreaterThanOrEqual(1);
      const phraseNode = allPhrases[0] as PhraseNode;
      expect(phraseNode.text).toBe('java python');
      expect(phraseNode.field).toBe('title');
    });

    it('supports Lucene syntax: phrases', () => {
      const result = parseEdismaxQuery('"hello world"', { qf: 'title content' });
      expect(result.errors).toEqual([]);
      // Unfielded phrase should be distributed across qf fields
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.should).toHaveLength(2);
    });

    it('supports Lucene syntax: NOT', () => {
      const result = parseEdismaxQuery('NOT title:java', { qf: 'title content' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('bool');
      const bool = result.ast as BoolNode;
      expect(bool.must_not).toHaveLength(1);
    });

    it('supports *:* as MatchAllNode', () => {
      const result = parseEdismaxQuery('*:*', { qf: 'title content' });
      expect(result.errors).toEqual([]);
      expect(result.ast.type).toBe('matchAll');
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 10: eDisMax distributes unfielded terms across qf fields
  // **Validates: Requirements 3.2, 3.3, 3.5**
  describe('Property 10: eDisMax distributes unfielded terms across qf fields', () => {
    it('unfielded term produces BoolNode(should) with one entry per qf field and correct boosts', () => {
      fc.assert(
        fc.property(
          arbValue(),
          fc.array(
            fc.tuple(arbFieldName(), fc.option(arbBoostInt(), { nil: undefined })),
            { minLength: 2, maxLength: 4 },
          ),
          (value, fieldPairs) => {
            // Deduplicate field names
            const seen = new Set<string>();
            const uniquePairs = fieldPairs.filter(([f]) => {
              if (seen.has(f)) return false;
              seen.add(f);
              return true;
            });
            if (uniquePairs.length < 2) return; // need at least 2 fields

            const qf = uniquePairs
              .map(([f, b]) => (b !== undefined ? `${f}^${b}` : f))
              .join(' ');

            const result = parseEdismaxQuery(value, { qf });
            expect(result.errors).toEqual([]);
            expect(result.ast.type).toBe('bool');

            const bool = result.ast as BoolNode;
            expect(bool.should).toHaveLength(uniquePairs.length);
            expect(bool.must).toHaveLength(0);
            expect(bool.must_not).toHaveLength(0);

            // Each should clause should target the correct qf field
            for (let i = 0; i < uniquePairs.length; i++) {
              const [expectedField, expectedBoost] = uniquePairs[i];
              const clause = bool.should[i];

              if (expectedBoost !== undefined && expectedBoost !== 1) {
                // Should be wrapped in BoostNode
                expect(clause.type).toBe('boost');
                const boost = clause as BoostNode;
                expect(boost.value).toBe(expectedBoost);
                expect(boost.child.type).toBe('field');
                expect((boost.child as FieldNode).field).toBe(expectedField);
                expect((boost.child as FieldNode).value).toBe(value);
              } else {
                // Plain FieldNode (boost 1.0)
                expect(clause.type).toBe('field');
                expect((clause as FieldNode).field).toBe(expectedField);
                expect((clause as FieldNode).value).toBe(value);
              }
            }
          },
        ),
        { numRuns: 100 },
      );
    });

    it('unfielded term with single qf field gets correct boost', () => {
      fc.assert(
        fc.property(
          arbValue(),
          arbFieldName(),
          arbBoostInt(),
          (value, field, boost) => {
            const qf = `${field}^${boost}`;
            const result = parseEdismaxQuery(value, { qf });
            expect(result.errors).toEqual([]);

            if (boost !== 1) {
              expect(result.ast.type).toBe('boost');
              const boostNode = result.ast as BoostNode;
              expect(boostNode.value).toBe(boost);
              expect((boostNode.child as FieldNode).field).toBe(field);
              expect((boostNode.child as FieldNode).value).toBe(value);
            } else {
              expect(result.ast.type).toBe('field');
              expect((result.ast as FieldNode).field).toBe(field);
              expect((result.ast as FieldNode).value).toBe(value);
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 11: eDisMax preserves explicit field targeting
  // **Validates: Requirements 3.6**
  describe('Property 11: eDisMax preserves explicit field targeting', () => {
    it('explicitly fielded terms produce a single FieldNode, not distributed across qf', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbQfFields(),
          (explicitField, value, qf) => {
            const query = `${explicitField}:${value}`;
            const result = parseEdismaxQuery(query, { qf });
            expect(result.errors).toEqual([]);

            // The result should be a single FieldNode targeting only the explicit field
            expect(result.ast.type).toBe('field');
            const field = result.ast as FieldNode;
            expect(field.field).toBe(explicitField);
            expect(field.value).toBe(value);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('explicitly fielded terms in AND expressions are not distributed', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbFieldName(),
          arbValue(),
          arbQfFields(),
          (f1, v1, f2, v2, qf) => {
            const query = `${f1}:${v1} AND ${f2}:${v2}`;
            const result = parseEdismaxQuery(query, { qf });
            expect(result.errors).toEqual([]);

            expect(result.ast.type).toBe('bool');
            const bool = result.ast as BoolNode;
            expect(bool.must).toHaveLength(2);

            // Both should be plain FieldNodes, not distributed
            expect(bool.must[0].type).toBe('field');
            expect((bool.must[0] as FieldNode).field).toBe(f1);
            expect((bool.must[0] as FieldNode).value).toBe(v1);
            expect(bool.must[1].type).toBe('field');
            expect((bool.must[1] as FieldNode).field).toBe(f2);
            expect((bool.must[1] as FieldNode).value).toBe(v2);
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 12: eDisMax supports Lucene syntax
  // **Validates: Requirements 3.7**
  describe('Property 12: eDisMax supports Lucene syntax', () => {
    it('explicitly fielded Lucene queries produce structurally equivalent ASTs', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbQfFields(),
          (field, value, qf) => {
            const query = `${field}:${value}`;
            const edismaxResult = parseEdismaxQuery(query, { qf });
            const luceneResult = parseLuceneQuery(query);

            expect(edismaxResult.errors).toEqual([]);
            expect(luceneResult.errors).toEqual([]);
            expect(astEqual(edismaxResult.ast, luceneResult.ast)).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('AND of explicitly fielded terms produces equivalent AST', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbFieldName(),
          arbValue(),
          arbQfFields(),
          (f1, v1, f2, v2, qf) => {
            const query = `${f1}:${v1} AND ${f2}:${v2}`;
            const edismaxResult = parseEdismaxQuery(query, { qf });
            const luceneResult = parseLuceneQuery(query);

            expect(edismaxResult.errors).toEqual([]);
            expect(luceneResult.errors).toEqual([]);
            expect(astEqual(edismaxResult.ast, luceneResult.ast)).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('OR of explicitly fielded terms produces equivalent AST', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbFieldName(),
          arbValue(),
          arbQfFields(),
          (f1, v1, f2, v2, qf) => {
            const query = `${f1}:${v1} OR ${f2}:${v2}`;
            const edismaxResult = parseEdismaxQuery(query, { qf });
            const luceneResult = parseLuceneQuery(query);

            expect(edismaxResult.errors).toEqual([]);
            expect(luceneResult.errors).toEqual([]);
            expect(astEqual(edismaxResult.ast, luceneResult.ast)).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('NOT of explicitly fielded term produces equivalent AST', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbQfFields(),
          (field, value, qf) => {
            const query = `NOT ${field}:${value}`;
            const edismaxResult = parseEdismaxQuery(query, { qf });
            const luceneResult = parseLuceneQuery(query);

            expect(edismaxResult.errors).toEqual([]);
            expect(luceneResult.errors).toEqual([]);
            expect(astEqual(edismaxResult.ast, luceneResult.ast)).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('boosted explicitly fielded term produces equivalent AST', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          arbBoostInt(),
          arbQfFields(),
          (field, value, boost, qf) => {
            const query = `${field}:${value}^${boost}`;
            const edismaxResult = parseEdismaxQuery(query, { qf });
            const luceneResult = parseLuceneQuery(query);

            expect(edismaxResult.errors).toEqual([]);
            expect(luceneResult.errors).toEqual([]);
            expect(astEqual(edismaxResult.ast, luceneResult.ast)).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('*:* produces equivalent MatchAllNode', () => {
      fc.assert(
        fc.property(
          arbQfFields(),
          (qf) => {
            const query = '*:*';
            const edismaxResult = parseEdismaxQuery(query, { qf });
            const luceneResult = parseLuceneQuery(query);

            expect(edismaxResult.errors).toEqual([]);
            expect(luceneResult.errors).toEqual([]);
            expect(astEqual(edismaxResult.ast, luceneResult.ast)).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
