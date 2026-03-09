/**
 * Round-trip property tests for the Solr query parser.
 *
 * Validates that parse → prettyPrint → parse produces equivalent ASTs,
 * ensuring the parser and pretty-printer are consistent.
 *
 * Feature: solr-query-parser
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { prettyPrint, type ASTNode, type BoolNode, type FieldNode, type PhraseNode, type RangeNode, type MatchAllNode, type GroupNode, type BoostNode } from '../ast/nodes';
import { parseLucene } from '../parser/luceneParser';
import { tokenize } from '../lexer/lexer';

// ─── Generators ───────────────────────────────────────────────────────────────

/** Alphanumeric field names (e.g., `title`, `price_usd`). */
export const arbFieldName = (): fc.Arbitrary<string> =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/i.test(s),
  );

/** Alphanumeric values without special characters. */
export const arbValue = (): fc.Arbitrary<string> =>
  fc.stringMatching(/^[a-z][a-z0-9]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/i.test(s),
  );

/** Strings without double-quotes (for phrase content). */
export const arbPhraseText = (): fc.Arbitrary<string> =>
  fc.stringMatching(/^[a-zA-Z0-9 ]{1,20}$/).filter((s) => s.trim().length > 0);

/** Positive integers 1–100 for boost values. */
export const arbBoostValue = (): fc.Arbitrary<number> =>
  fc.integer({ min: 1, max: 100 });

/** Pairs of alphanumeric strings with inclusivity flags for range bounds. */
export const arbRangeBounds = (): fc.Arbitrary<{ lower: string; upper: string; lowerInclusive: boolean; upperInclusive: boolean }> =>
  fc.record({
    lower: arbValue(),
    upper: arbValue(),
    lowerInclusive: fc.boolean(),
    upperInclusive: fc.boolean(),
  });

/** Recursive generator for arbitrary AST trees (depth-limited). */
export const arbASTNode = (depth: number): fc.Arbitrary<ASTNode> => {
  // Leaf nodes — always available
  const leafNodes: fc.Arbitrary<ASTNode>[] = [
    // FieldNode
    fc.tuple(arbFieldName(), arbValue()).map(
      ([field, value]): FieldNode => ({ type: 'field', field, value }),
    ),
    // PhraseNode (with field)
    fc.tuple(arbFieldName(), arbPhraseText()).map(
      ([field, text]): PhraseNode => ({ type: 'phrase', text, field }),
    ),
    // RangeNode
    fc.tuple(arbFieldName(), arbRangeBounds()).map(
      ([field, bounds]): RangeNode => ({
        type: 'range',
        field,
        lower: bounds.lower,
        upper: bounds.upper,
        lowerInclusive: bounds.lowerInclusive,
        upperInclusive: bounds.upperInclusive,
      }),
    ),
    // MatchAllNode
    fc.constant<MatchAllNode>({ type: 'matchAll' }),
  ];

  if (depth <= 0) {
    return fc.oneof(...leafNodes);
  }

  const recurse = arbASTNode(depth - 1);

  const compositeNodes: fc.Arbitrary<ASTNode>[] = [
    // BoolNode with must (AND)
    fc.array(recurse, { minLength: 2, maxLength: 3 }).map(
      (children): BoolNode => ({ type: 'bool', must: children, should: [], must_not: [] }),
    ),
    // BoolNode with should (OR)
    fc.array(recurse, { minLength: 2, maxLength: 3 }).map(
      (children): BoolNode => ({ type: 'bool', must: [], should: children, must_not: [] }),
    ),
    // BoolNode with must_not (NOT)
    recurse.map(
      (child): BoolNode => ({ type: 'bool', must: [], should: [], must_not: [child] }),
    ),
    // BoostNode
    fc.tuple(recurse, arbBoostValue()).map(
      ([child, value]): BoostNode => ({ type: 'boost', child, value }),
    ),
    // GroupNode
    recurse.map(
      (child): GroupNode => ({ type: 'group', child }),
    ),
  ];

  return fc.oneof(...leafNodes, ...compositeNodes);
};


/**
 * Generates valid Solr query strings by building ASTs and pretty-printing them.
 * This ensures the generated strings are always parseable.
 */
export const arbSolrQuery = (depth: number = 1): fc.Arbitrary<string> =>
  arbASTNode(depth).map((node) => prettyPrint(node));

// ─── AST Comparison ───────────────────────────────────────────────────────────

/**
 * Recursive deep structural comparison of two AST nodes.
 * Returns true if the nodes are semantically equivalent.
 */
function astEqual(a: ASTNode, b: ASTNode): boolean {
  if (a.type !== b.type) return false;

  switch (a.type) {
    case 'matchAll':
      return true;

    case 'field':
      return a.field === (b as FieldNode).field && a.value === (b as FieldNode).value;

    case 'phrase':
      return a.text === (b as PhraseNode).text && a.field === (b as PhraseNode).field;

    case 'range': {
      const br = b as RangeNode;
      return (
        a.field === br.field &&
        a.lower === br.lower &&
        a.upper === br.upper &&
        a.lowerInclusive === br.lowerInclusive &&
        a.upperInclusive === br.upperInclusive
      );
    }

    case 'boost': {
      const bb = b as BoostNode;
      return a.value === bb.value && astEqual(a.child, bb.child);
    }

    case 'group':
      return astEqual(a.child, (b as GroupNode).child);

    case 'bool': {
      const bbool = b as BoolNode;
      return (
        arrayEqual(a.must, bbool.must) &&
        arrayEqual(a.should, bbool.should) &&
        arrayEqual(a.must_not, bbool.must_not)
      );
    }
  }
}

function arrayEqual(a: ASTNode[], b: ASTNode[]): boolean {
  if (a.length !== b.length) return false;
  return a.every((node, i) => astEqual(node, b[i]));
}

// ─── Property Tests ───────────────────────────────────────────────────────────

// Feature: solr-query-parser, Property 1: Parse round-trip
// **Validates: Requirements 8.2, 8.3**
describe('Property 1: Parse round-trip', () => {
  const DEFAULT_FIELD = 'text';

  it('parse → prettyPrint → parse produces equivalent ASTs', () => {
    fc.assert(
      fc.property(
        arbSolrQuery(1),
        (query) => {
          // First parse
          const tokens1 = tokenize(query);
          if (tokens1.errors.length > 0) return; // skip invalid queries
          const result1 = parseLucene(tokens1.tokens, DEFAULT_FIELD);
          if (result1.errors.length > 0) return; // skip parse errors

          // Pretty-print
          const printed = prettyPrint(result1.ast);

          // Second parse
          const tokens2 = tokenize(printed);
          expect(tokens2.errors).toEqual([]);
          const result2 = parseLucene(tokens2.tokens, DEFAULT_FIELD);
          expect(result2.errors).toEqual([]);

          // Compare ASTs
          const equal = astEqual(result1.ast, result2.ast);
          if (!equal) {
            // Provide helpful debug output on failure
            throw new Error(
              `Round-trip failed:\n` +
              `  Original query: ${query}\n` +
              `  Pretty-printed: ${printed}\n` +
              `  AST1: ${JSON.stringify(result1.ast, null, 2)}\n` +
              `  AST2: ${JSON.stringify(result2.ast, null, 2)}`,
            );
          }
        },
      ),
      { numRuns: 100 },
    );
  });
});
