/**
 * Transformation rule for BoolNode → OpenSearch `bool` query.
 *
 * Maps Solr's AND/OR/NOT boolean operators to OpenSearch's bool query clauses:
 *   BoolNode.and → bool.must    (all clauses required)
 *   BoolNode.or  → bool.should  (at least one clause matches)
 *   BoolNode.not → bool.must_not (clauses excluded)
 *
 * Example:
 *   Input: BoolNode { and: [FieldNode(title,java), RangeNode(price,10,100)], or: [], not: [] }
 *   Output: Map{"bool" → Map{"must" → [Map{"match"→...}, Map{"range"→...}]}}
 *
 * Empty clause arrays are omitted from the output Map — only clauses with
 * children are included. For example, `title:java AND author:smith` has
 * and=[...] but or=[] and not=[], so the output is:
 *   Map{"bool" → Map{"must" → [...]}}
 * not:
 *   Map{"bool" → Map{"must" → [...], "should" → [], "must_not" → []}}
 * This produces cleaner DSL matching what OpenSearch clients typically generate.
 */

import type { ASTNode, BoolNode } from '../../ast/nodes';
import type { TransformRuleFn, TransformChild } from '../types';

export const boolRule: TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
): Map<string, any> => {
  const { and, or, not } = node as BoolNode;
  const boolMap = new Map<string, any>();

  // TODO: Flattening will help in query optimizations
  if (and.length > 0) {
    boolMap.set('must', and.map(transformChild));
  }
  if (or.length > 0) {
    boolMap.set('should', or.map(transformChild));
    // In OpenSearch, when `must` is present, `should` clauses become optional
    // (only affect scoring). In Solr, `A AND (B OR C)` requires at least one
    // of B or C to match. Add minimum_should_match to enforce this.
    if (and.length > 0) {
      boolMap.set('minimum_should_match', 1);
    }
  }
  if (not.length > 0) {
    boolMap.set('must_not', not.map(transformChild));
  }

  return new Map([['bool', boolMap]]);
};
