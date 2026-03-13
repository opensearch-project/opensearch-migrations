/**
 * Transformation rule for BoolNode → OpenSearch `bool` query.
 *
 * Maps Solr's AND/OR/NOT boolean operators to OpenSearch's bool query clauses:
 *   BoolNode.and → bool.must    (all clauses required)
 *   BoolNode.or  → bool.should  (at least one clause matches)
 *   BoolNode.not → bool.must_not (clauses excluded)
 *
 * Each child node in the arrays is recursively transformed by the dispatcher
 * (astToOpenSearch.ts), not by this rule. This rule transforms the children
 * via the callback and assembles the results into the bool query structure.
 *
 * How transformChild works:
 *   A BoolNode's children can be any AST node type — FieldNode, RangeNode,
 *   another BoolNode, etc. This rule doesn't know how to transform those
 *   children; that's the dispatcher's job. So the dispatcher passes its own
 *   `transformNode` function as the `transformChild` callback. This rule
 *   calls it on each child to get the transformed Map, then wraps the
 *   results in the bool structure.
 *
 *   Flow:
 *     1. Dispatcher calls transformBool(boolNode, transformNode)
 *     2. This rule calls transformChild(child) for each child in and/or/not
 *     3. transformChild dispatches to the correct rule for that child's type
 *     4. This rule collects the resulting Maps into must/should/must_not arrays
 *     5. Returns Map{"bool" → Map{...}}
 *
 *   This avoids a circular import (dispatcher imports rule, rule imports
 *   dispatcher) and makes the rule testable with a mock callback.
 *
 * Example:
 *   Input: BoolNode { and: [FieldNode(title,java), RangeNode(price,10,100)], or: [], not: [] }
 *   Output: Map{"bool" → Map{"must" → [Map{"term"→...}, Map{"range"→...}]}}
 *
 * Empty clause arrays are omitted from the output Map.
 */

import type { BoolNode } from '../../ast/nodes';

/**
 * Transform a BoolNode into an OpenSearch bool query Map.
 *
 * @param node - The BoolNode to transform
 * @param transformChild - Callback provided by the dispatcher to recursively
 *                         transform child AST nodes into OpenSearch DSL Maps.
 * @returns Map{"bool" → Map{...clauses...}}
 */
export function transformBool(
  node: BoolNode,
  transformChild: (child: import('../../ast/nodes').ASTNode) => Map<string, any>,
): Map<string, any> {
  // TODO: implement
  throw new Error('Not implemented');
}
