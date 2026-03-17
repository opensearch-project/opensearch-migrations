/**
 * Shared types for the transformer layer.
 */

import type { ASTNode } from '../ast/nodes';

/**
 * A callback that recursively transforms a child AST node into
 * OpenSearch DSL. Provided by the dispatcher to rules that have
 * children (BoolNode, GroupNode, BoostNode).
 */
export type TransformChild = (child: ASTNode) => Map<string, any>;

/**
 * A function that transforms an AST node into an OpenSearch DSL Map.
 *
 * Each AST node type has a corresponding TransformRuleFn. The dispatcher
 * looks up the function by `node.type` and calls it.
 *
 * Rules for leaf nodes (FieldNode, PhraseNode, RangeNode, MatchAllNode)
 * can ignore the `transformChild` parameter. Rules for composite nodes
 * (BoolNode, GroupNode, BoostNode) use it to recurse into children.
 *
 * Example (leaf rule):
 *   (FieldNode { field: "title", value: "java" }, _)
 *   → Map{"term" → Map{"title" → "java"}}
 *
 * Example (composite rule):
 *   (BoolNode { and: [FieldNode, RangeNode], ... }, transformChild)
 *   → Map{"bool" → Map{"must" → [transformChild(FieldNode), transformChild(RangeNode)]}}
 */
export type TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
) => Map<string, any>;
