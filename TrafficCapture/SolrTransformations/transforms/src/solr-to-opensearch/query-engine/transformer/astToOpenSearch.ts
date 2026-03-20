/**
 * AST-to-OpenSearch transformer.
 *
 * Converts Solr AST nodes into OpenSearch Query DSL as nested Maps.
 * Uses a registry of transform functions — one per AST node type.
 * The dispatcher looks up the function by `node.type` and calls it.
 *
 * IMPORTANT: All output must use `new Map()` — never plain JS objects (`{}`).
 * This is a GraalVM runtime requirement, not a style choice. The Java-side
 * Jackson serializer accesses JS Maps directly as Java LinkedHashMaps via
 * GraalVM's `allowMapAccess(true)`. Plain objects don't get this bridge and
 * will cause serialization failures at runtime.
 *
 * Transformation rules (Solr syntax → AST node → OpenSearch DSL):
 *
 *   `*:*`
 *     MatchAllNode → Map{"match_all" → Map{}}
 *
 *   `title:java`
 *     FieldNode(title, java) → Map{"term" → Map{"title" → "java"}}
 *
 *   `"hello world"` (with df="content")
 *     PhraseNode(hello world) → Map{"match_phrase" → Map{"content" → "hello world"}}
 *
 *   `title:java AND author:smith`
 *     BoolNode(and: [...]) → Map{"bool" → Map{"must" → [...]}}
 *
 *   `price:[10 TO 100]`
 *     RangeNode(price, 10, 100) → Map{"range" → Map{"price" → Map{"gte" → "10", "lte" → "100"}}}
 *
 *   `title:java^2`
 *     BoostNode(FieldNode, 2) → Map{"term" → Map{"title" → "java", "boost" → 2}}
 *
 *   `(a OR b)`
 *     GroupNode → transparent, recurses into child
 */

import type { ASTNode } from '../ast/nodes';

/**
 * Transform an AST node into an OpenSearch DSL Map.
 *
 * Looks up the TransformRuleFn for the node's type from the registry
 * and calls it. The parser is expected to have resolved all field
 * names — including applying the default field (df) to bare phrases
 * and values.
 *
 * @param node - The AST node to transform
 * @returns A nested Map structure representing OpenSearch Query DSL
 */
export function transformNode(node: ASTNode): Map<string, any> {
  // TODO: implement
  // 1. Look up TransformRuleFn by node.type from the rules registry
  // 2. Call rule(node, transformNode) for recursive dispatch
  // 3. Throw if no rule registered for the node type — the orchestrator
  //    catches this and handles it based on the translation mode:
  //    - passthrough-on-error: returns query_string passthrough + warning
  //    - partial: skips the node, adds a warning, continues translating
  throw new Error('Not implemented');
}
