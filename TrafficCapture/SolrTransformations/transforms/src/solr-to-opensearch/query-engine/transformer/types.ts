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
 * `fieldTypes` is optional schema metadata (field name → Solr fieldType class).
 * Currently only `fieldRule` uses it to choose `term` vs `match`. All other
 * rules can ignore it.
 *
 * Example (leaf rule):
 *   (FieldNode { field: "title", value: "java" }, _transformChild)
 *   → Map{"match" → Map{"title" → "java"}}
 *
 * Example (composite rule):
 *   (BoolNode { and: [FieldNode, RangeNode], ... }, transformChild)
 *   → Map{"bool" → Map{"must" → [transformChild(FieldNode), transformChild(RangeNode)]}}
 */
export type TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
  fieldTypes?: ReadonlyMap<string, string>,
) => Map<string, any>;
