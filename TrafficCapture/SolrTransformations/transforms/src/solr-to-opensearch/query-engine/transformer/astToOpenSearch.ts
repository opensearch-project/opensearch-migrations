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
 *     FieldNode(title, java) → Map{"match" → Map{"title" → "java"}}
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
 *     BoostNode(FieldNode, 2) → Map{"match" → Map{"title" → Map{"query" → "java", "boost" → 2}}}
 *
 *   `(a OR b)`
 *     GroupNode → transparent, recurses into child
 */

import type { ASTNode } from '../ast/nodes';
import type { TransformRuleFn } from './types';
import { bareRule } from './rules/bareRule';
import { boolRule } from './rules/boolRule';
import { fieldRule } from './rules/fieldRule';
import { filterRule } from './rules/filterRule';
import { matchAllRule } from './rules/matchAllRule';
import { phraseRule } from './rules/phraseRule';
import { boostRule } from './rules/boostRule';
import { rangeRule } from './rules/rangeRule';

/**
 * Registry of transform functions, keyed by AST node type.
 *
 * To add support for a new AST node type:
 *   1. Create a TransformRuleFn in transformer/rules/
 *   2. Register it here with the node's `type` discriminant as the key
 *
 * Note: 'group' is handled inline in transformNode() as a pass-through
 * since OpenSearch has no grouping concept — precedence is handled by
 * nesting bool queries.
 */
const rules: Record<string, TransformRuleFn> = {
  // TODO: register remaining rules as they are implemented
  bare: bareRule,
  bool: boolRule,
  field: fieldRule,
  filter: filterRule,
  matchAll: matchAllRule,
  phrase: phraseRule,
  range: rangeRule,
  boost: boostRule,
};

/**
 * Transform an AST node into an OpenSearch DSL Map.
 *
 * Looks up the TransformRuleFn for the node's type from the registry
 * and calls it. The parser is expected to have resolved all field
 * names — including applying the default field (df) to bare phrases
 * and values.
 *
 * @param node - The AST node to transform
 * @param fieldTypes - Map of field name → Solr fieldType class from managed-schema.xml.
 *                     Passed to every rule; currently used by fieldRule to choose
 *                     term vs match. Empty map = match for all fields (safe default).
 * @returns A nested Map structure representing OpenSearch Query DSL
 * @throws Error if no rule is registered for the node type — the orchestrator
 *         catches this and handles it based on the translation mode:
 *         - passthrough-on-error: returns query_string passthrough + warning
 *         - partial: skips the node, adds a warning, continues translating
 */
export function transformNode(
  node: ASTNode,
  fieldTypes: ReadonlyMap<string, string> = new Map(),
): Map<string, any> {
  // Build a transformChild closure that carries fieldTypes down the tree.
  // All composite rules (bool, boost, filter, group) call transformChild to
  // recurse — this ensures fieldTypes propagates to every FieldNode in the tree.
  const transformChild = (child: ASTNode) => transformNode(child, fieldTypes);

  /**
   * GroupNode represents parentheses in Solr syntax, used to override operator
   * precedence. OpenSearch doesn't have an equivalent concept — precedence is
   * handled by nesting bool queries. This rule simply unwraps the group and
   * transforms its child.
   *
   * Example:
   *   Input: GroupNode { child: BoolNode { or: [FieldNode, FieldNode] } }
   *   Output: Map{"bool" → Map{"should" → [...]}}
   *
   * The GroupNode is transparent in the output — it doesn't produce any
   * OpenSearch DSL structure of its own.
   */
  if (node.type === 'group') {
    return transformNode(node.child, fieldTypes);
  }

  // LocalParamsNode: extract metadata and transform the body.
  // The local params metadata (type, qf, df, etc.) is available on node.params
  // for the orchestrator to use. The transformer only handles the body query.
  if (node.type === 'localParams') {
    if (node.body) {
      return transformNode(node.body, fieldTypes);
    }
    // No body — return match_all as default
    return new Map([['match_all', new Map()]]);
  }

  // FuncNode: no transform rule yet.
  if (node.type === 'func') {
    throw new Error('No transform rule registered for node type: func');
  }

  const rule = rules[node.type];
  if (!rule) {
    throw new Error(`No transform rule registered for node type: ${node.type}`);
  }
  return rule(node, transformChild, fieldTypes);
}
