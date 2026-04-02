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

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn, TransformChild } from '../types';

export const boolRule: TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
): Map<string, any> => {
  if (node.type !== 'bool') {
    throw new Error(`boolRule called with wrong node type: ${node.type}`);
  }
  const { and, or, not } = node;
  const boolMap = new Map<string, any>();

  if (and.length > 0) {
    boolMap.set('must', and.map(transformChild));
  }
  if (or.length > 0) {
    boolMap.set('should', or.map(transformChild));
  }
  if (not.length > 0) {
    boolMap.set('must_not', not.map(transformChild));
  }

  return new Map([['bool', boolMap]]);
};
