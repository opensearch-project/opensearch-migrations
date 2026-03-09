/**
 * Transformer tests — property-based tests for AST-to-OpenSearch transformation.
 *
 * Property tests use fast-check to validate correctness properties
 * from the design document.
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { transformNode } from '../transformer/astToOpenSearch';
import type {
  ASTNode,
  BoolNode,
  FieldNode,
  PhraseNode,
  RangeNode,
  MatchAllNode,
  BoostNode,
  GroupNode,
} from '../ast/nodes';

// ─── Generators ───────────────────────────────────────────────────────────────

/** Alphanumeric field names. */
const arbFieldName = () =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Alphanumeric values. */
const arbValue = () =>
  fc.stringMatching(/^[a-z][a-z0-9]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Phrase text — no double quotes inside. */
const arbPhraseText = () =>
  fc.stringMatching(/^[a-zA-Z0-9 ]{1,20}$/).filter((s) => s.length > 0);

/** Positive boost values. */
const arbBoostValue = () => fc.double({ min: 0.1, max: 100.0, noNaN: true });

/** Default field name for tests. */
const arbDf = () => fc.constantFrom('_text_', 'content', 'body');

/** Generate a FieldNode. */
const arbFieldNode = (): fc.Arbitrary<FieldNode> =>
  fc.tuple(arbFieldName(), arbValue()).map(([field, value]) => ({
    type: 'field' as const,
    field,
    value,
  }));

/** Generate a PhraseNode with a field. */
const arbPhraseNodeWithField = (): fc.Arbitrary<PhraseNode> =>
  fc.tuple(arbFieldName(), arbPhraseText()).map(([field, text]) => ({
    type: 'phrase' as const,
    text,
    field,
  }));

/** Generate a PhraseNode without a field. */
const arbPhraseNodeNoField = (): fc.Arbitrary<PhraseNode> =>
  arbPhraseText().map((text) => ({
    type: 'phrase' as const,
    text,
  }));

/** Generate a RangeNode. */
const arbRangeNode = (): fc.Arbitrary<RangeNode> =>
  fc.tuple(
    arbFieldName(),
    arbValue(),
    arbValue(),
    fc.boolean(),
    fc.boolean(),
  ).map(([field, lower, upper, lowerInclusive, upperInclusive]) => ({
    type: 'range' as const,
    field,
    lower,
    upper,
    lowerInclusive,
    upperInclusive,
  }));

/** Generate a leaf AST node (no recursion). */
const arbLeafNode = (): fc.Arbitrary<ASTNode> =>
  fc.oneof(
    arbFieldNode(),
    arbPhraseNodeWithField(),
    arbRangeNode(),
    fc.constant({ type: 'matchAll' } as MatchAllNode),
  );

/** Generate an arbitrary AST node with bounded depth. */
const arbASTNode = (maxDepth: number): fc.Arbitrary<ASTNode> => {
  if (maxDepth <= 0) return arbLeafNode();

  return fc.oneof(
    arbLeafNode(),
    // BoolNode with children
    fc.tuple(
      fc.array(arbASTNode(maxDepth - 1), { minLength: 0, maxLength: 2 }),
      fc.array(arbASTNode(maxDepth - 1), { minLength: 0, maxLength: 2 }),
      fc.array(arbASTNode(maxDepth - 1), { minLength: 0, maxLength: 2 }),
    ).filter(([must, should, must_not]) => must.length + should.length + must_not.length > 0)
     .map(([must, should, must_not]): BoolNode => ({
       type: 'bool',
       must,
       should,
       must_not,
     })),
    // GroupNode
    arbASTNode(maxDepth - 1).map((child): GroupNode => ({
      type: 'group',
      child,
    })),
    // BoostNode
    fc.tuple(arbASTNode(maxDepth - 1), arbBoostValue()).map(
      ([child, value]): BoostNode => ({
        type: 'boost',
        child,
        value,
      }),
    ),
  );
};


// ─── Helper ───────────────────────────────────────────────────────────────────

/** Recursively check that every non-primitive value in a Map tree is a Map. */
function assertAllMaps(value: any, path = 'root'): void {
  if (value instanceof Map) {
    for (const [k, v] of value.entries()) {
      assertAllMaps(v, `${path}.${k}`);
    }
  } else if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      assertAllMaps(value[i], `${path}[${i}]`);
    }
  } else if (typeof value === 'object' && value !== null) {
    throw new Error(`Found plain object at ${path}: ${JSON.stringify(value)}`);
  }
  // primitives (string, number, boolean, null, undefined) are fine
}

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('Transformer', () => {
  describe('unit tests', () => {
    it('transforms MatchAllNode to match_all', () => {
      const result = transformNode({ type: 'matchAll' }, '_text_');
      expect(result).toBeInstanceOf(Map);
      expect(result.get('match_all')).toBeInstanceOf(Map);
      expect(result.get('match_all').size).toBe(0);
    });

    it('transforms FieldNode to term query', () => {
      const result = transformNode({ type: 'field', field: 'title', value: 'java' }, '_text_');
      expect(result.get('term')).toBeInstanceOf(Map);
      expect(result.get('term').get('title')).toBe('java');
    });

    it('transforms PhraseNode with field to match_phrase', () => {
      const result = transformNode({ type: 'phrase', text: 'hello world', field: 'title' }, '_text_');
      expect(result.get('match_phrase')).toBeInstanceOf(Map);
      expect(result.get('match_phrase').get('title')).toBe('hello world');
    });

    it('transforms PhraseNode without field using df', () => {
      const result = transformNode({ type: 'phrase', text: 'hello world' }, 'content');
      expect(result.get('match_phrase').get('content')).toBe('hello world');
    });

    it('transforms RangeNode with inclusive bounds', () => {
      const node: RangeNode = {
        type: 'range', field: 'price', lower: '10', upper: '100',
        lowerInclusive: true, upperInclusive: true,
      };
      const result = transformNode(node, '_text_');
      const rangeMap = result.get('range').get('price');
      expect(rangeMap.get('gte')).toBe('10');
      expect(rangeMap.get('lte')).toBe('100');
    });

    it('transforms RangeNode with exclusive bounds', () => {
      const node: RangeNode = {
        type: 'range', field: 'price', lower: '10', upper: '100',
        lowerInclusive: false, upperInclusive: false,
      };
      const result = transformNode(node, '_text_');
      const rangeMap = result.get('range').get('price');
      expect(rangeMap.get('gt')).toBe('10');
      expect(rangeMap.get('lt')).toBe('100');
    });

    it('transforms BoolNode with must, should, must_not', () => {
      const node: BoolNode = {
        type: 'bool',
        must: [{ type: 'field', field: 'title', value: 'java' }],
        should: [{ type: 'field', field: 'title', value: 'python' }],
        must_not: [{ type: 'field', field: 'title', value: 'cobol' }],
      };
      const result = transformNode(node, '_text_');
      const boolMap = result.get('bool');
      expect(boolMap).toBeInstanceOf(Map);
      expect(boolMap.get('must')).toHaveLength(1);
      expect(boolMap.get('should')).toHaveLength(1);
      expect(boolMap.get('must_not')).toHaveLength(1);
    });

    it('unwraps single-clause must BoolNode', () => {
      const node: BoolNode = {
        type: 'bool',
        must: [{ type: 'field', field: 'title', value: 'java' }],
        should: [],
        must_not: [],
      };
      const result = transformNode(node, '_text_');
      // Should unwrap to just the term query
      expect(result.has('term')).toBe(true);
      expect(result.has('bool')).toBe(false);
    });

    it('unwraps single-clause should BoolNode', () => {
      const node: BoolNode = {
        type: 'bool',
        must: [],
        should: [{ type: 'field', field: 'title', value: 'java' }],
        must_not: [],
      };
      const result = transformNode(node, '_text_');
      expect(result.has('term')).toBe(true);
      expect(result.has('bool')).toBe(false);
    });

    it('does NOT unwrap single-clause must_not BoolNode', () => {
      const node: BoolNode = {
        type: 'bool',
        must: [],
        should: [],
        must_not: [{ type: 'field', field: 'title', value: 'java' }],
      };
      const result = transformNode(node, '_text_');
      expect(result.has('bool')).toBe(true);
    });

    it('transforms BoostNode — adds boost to inner map', () => {
      const node: BoostNode = {
        type: 'boost',
        child: { type: 'field', field: 'title', value: 'java' },
        value: 2,
      };
      const result = transformNode(node, '_text_');
      expect(result.get('term').get('boost')).toBe(2);
    });

    it('transforms GroupNode transparently', () => {
      const node: GroupNode = {
        type: 'group',
        child: { type: 'field', field: 'title', value: 'java' },
      };
      const result = transformNode(node, '_text_');
      expect(result.get('term').get('title')).toBe('java');
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 13: Transformer produces correct DSL for each node type
  // **Validates: Requirements 5.2, 5.3, 5.4, 5.6**
  describe('Property 13: Transformer produces correct DSL for each node type', () => {
    it('FieldNode produces Map{"term" → Map{field → value}}', () => {
      fc.assert(
        fc.property(
          arbFieldNode(),
          arbDf(),
          (node, df) => {
            const result = transformNode(node, df);
            expect(result).toBeInstanceOf(Map);
            expect(result.has('term')).toBe(true);
            const termMap = result.get('term');
            expect(termMap).toBeInstanceOf(Map);
            expect(termMap.get(node.field)).toBe(node.value);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('PhraseNode with field produces Map{"match_phrase" → Map{field → text}}', () => {
      fc.assert(
        fc.property(
          arbPhraseNodeWithField(),
          arbDf(),
          (node, df) => {
            const result = transformNode(node, df);
            expect(result).toBeInstanceOf(Map);
            expect(result.has('match_phrase')).toBe(true);
            const phraseMap = result.get('match_phrase');
            expect(phraseMap).toBeInstanceOf(Map);
            expect(phraseMap.get(node.field!)).toBe(node.text);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('PhraseNode without field uses df', () => {
      fc.assert(
        fc.property(
          arbPhraseNodeNoField(),
          arbDf(),
          (node, df) => {
            const result = transformNode(node, df);
            const phraseMap = result.get('match_phrase');
            expect(phraseMap.get(df)).toBe(node.text);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('RangeNode produces correct bounds based on inclusivity', () => {
      fc.assert(
        fc.property(
          arbRangeNode(),
          arbDf(),
          (node, df) => {
            const result = transformNode(node, df);
            expect(result.has('range')).toBe(true);
            const rangeMap = result.get('range');
            expect(rangeMap).toBeInstanceOf(Map);
            const fieldMap = rangeMap.get(node.field);
            expect(fieldMap).toBeInstanceOf(Map);

            if (node.lowerInclusive) {
              expect(fieldMap.get('gte')).toBe(node.lower);
              expect(fieldMap.has('gt')).toBe(false);
            } else {
              expect(fieldMap.get('gt')).toBe(node.lower);
              expect(fieldMap.has('gte')).toBe(false);
            }

            if (node.upperInclusive) {
              expect(fieldMap.get('lte')).toBe(node.upper);
              expect(fieldMap.has('lt')).toBe(false);
            } else {
              expect(fieldMap.get('lt')).toBe(node.upper);
              expect(fieldMap.has('lte')).toBe(false);
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });


  // Feature: solr-query-parser, Property 14: Transformer bool clause recursion
  // **Validates: Requirements 5.5**
  describe('Property 14: Transformer bool clause recursion', () => {
    it('BoolNode with arbitrary children produces correct recursive Map structure', () => {
      fc.assert(
        fc.property(
          fc.tuple(
            fc.array(arbLeafNode(), { minLength: 0, maxLength: 3 }),
            fc.array(arbLeafNode(), { minLength: 0, maxLength: 3 }),
            fc.array(arbLeafNode(), { minLength: 0, maxLength: 3 }),
          ).filter(([must, should, must_not]) => {
            const total = must.length + should.length + must_not.length;
            // Need more than 1 total clause to avoid unwrapping
            return total > 1;
          }),
          arbDf(),
          ([must, should, must_not], df) => {
            const node: BoolNode = { type: 'bool', must, should, must_not };
            const result = transformNode(node, df);

            expect(result).toBeInstanceOf(Map);
            expect(result.has('bool')).toBe(true);
            const boolMap = result.get('bool');
            expect(boolMap).toBeInstanceOf(Map);

            // Verify each clause array has the right number of recursively transformed children
            if (must.length > 0) {
              const mustArr = boolMap.get('must');
              expect(mustArr).toHaveLength(must.length);
              for (const child of mustArr) {
                expect(child).toBeInstanceOf(Map);
              }
            }
            if (should.length > 0) {
              const shouldArr = boolMap.get('should');
              expect(shouldArr).toHaveLength(should.length);
              for (const child of shouldArr) {
                expect(child).toBeInstanceOf(Map);
              }
            }
            if (must_not.length > 0) {
              const mustNotArr = boolMap.get('must_not');
              expect(mustNotArr).toHaveLength(must_not.length);
              for (const child of mustNotArr) {
                expect(child).toBeInstanceOf(Map);
              }
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });


  // Feature: solr-query-parser, Property 15: Transformer bool unwrapping
  // **Validates: Requirements 5.9**
  describe('Property 15: Transformer bool unwrapping', () => {
    it('BoolNode with exactly one clause in must or should unwraps to child directly', () => {
      fc.assert(
        fc.property(
          arbLeafNode(),
          fc.constantFrom('must', 'should') as fc.Arbitrary<'must' | 'should'>,
          arbDf(),
          (child, clause, df) => {
            const node: BoolNode = {
              type: 'bool',
              must: clause === 'must' ? [child] : [],
              should: clause === 'should' ? [child] : [],
              must_not: [],
            };
            const result = transformNode(node, df);

            // Should NOT have a bool wrapper
            expect(result.has('bool')).toBe(false);

            // Should be the direct transformation of the child
            const directResult = transformNode(child, df);
            // Compare the first key — they should match
            const resultKey = result.keys().next().value;
            const directKey = directResult.keys().next().value;
            expect(resultKey).toBe(directKey);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('BoolNode with single must_not does NOT unwrap', () => {
      fc.assert(
        fc.property(
          arbLeafNode(),
          arbDf(),
          (child, df) => {
            const node: BoolNode = {
              type: 'bool',
              must: [],
              should: [],
              must_not: [child],
            };
            const result = transformNode(node, df);

            // must_not cannot be unwrapped — needs bool wrapper
            expect(result.has('bool')).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });
  });


  // Feature: solr-query-parser, Property 16: All transformer output uses Maps
  // **Validates: Requirements 5.8**
  describe('Property 16: All transformer output uses Maps', () => {
    it('every non-primitive value in output is instanceof Map', () => {
      fc.assert(
        fc.property(
          arbASTNode(2),
          arbDf(),
          (node, df) => {
            const result = transformNode(node, df);
            expect(result).toBeInstanceOf(Map);
            // Recursively verify no plain objects
            assertAllMaps(result);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
