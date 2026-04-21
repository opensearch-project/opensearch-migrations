/**
 * Shared types for the transformer layer.
 */

import type { ASTNode } from '../ast/nodes';

/**
 * Field metadata map — field name to OpenSearch field type.
 * Used by transform rules to choose the correct query type.
 * Example: { "title": "text", "category": "keyword", "price": "float" }
 */
export type FieldMappings = ReadonlyMap<string, string>;

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
 *   → Map{"match" → Map{"title" → "java"}}
 *
 * Example (composite rule):
 *   (BoolNode { and: [FieldNode, RangeNode], ... }, transformChild)
 *   → Map{"bool" → Map{"must" → [transformChild(FieldNode), transformChild(RangeNode)]}}
 *
 * @param node - The AST node to transform
 * @param transformChild - Callback for recursive child transformation
 * @param fieldMappings - Optional field name → OpenSearch type map for query type selection
 */
export type TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
  fieldMappings?: FieldMappings,
) => Map<string, any>;
