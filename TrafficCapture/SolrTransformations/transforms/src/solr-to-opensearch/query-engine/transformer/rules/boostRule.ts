/**
 * Transformation rule for BoostNode → OpenSearch query with `boost` parameter.
 *
 * Applies Solr's boost modifier (^N) to any query type by adding the `boost`
 * parameter to the child query's OpenSearch DSL output.
 *
 * IMPORTANT: Child transformers must return output in one of two patterns
 * (field-level or query-level). See @link {../../README.md} for details.
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn, TransformChild } from '../types';

export const boostRule: TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
): Map<string, any> => {
  if (node.type !== 'boost') {
    const msg = `[boostRule] Called with wrong node type: ${node.type}`;
    console.error(msg);
    throw new Error(msg);
  }

  const { child, value: boostValue } = node;

  // Transform the child node first
  const childResult = transformChild(child);

  // validation: child must return a non-empty Map
  if (!(childResult instanceof Map) || childResult.size === 0) {
    const msg = `[boostRule] Child transformer returned invalid result: expected non-empty Map`;
    console.error(msg);
    throw new Error(msg);
  }

  // Get the query type and body
  // e.g., for {"term": {"title": {"value": "java"}}}
  //       queryType = "term", queryBody = Map{"title" → Map{...}}
  const queryType = childResult.keys().next().value as string;
  const queryBody = childResult.get(queryType);

  // Basic validation: query body must be a Map
  if (!(queryBody instanceof Map)) {
    const msg = `[boostRule] Query body for type '${queryType}' is not a Map`;
    console.error(msg);
    throw new Error(msg);
  }

  // Detect structure by checking if the first value is a Map (field-level)
  // or a primitive like string/array (query-level).
  //
  // Field-level example: {"term": {"title": {"value": "java"}}}
  //   queryBody = Map{"title" → Map{"value" → "java"}}
  //   firstValue = Map{"value" → "java"} → instanceof Map = true
  //   → Add boost to firstValue: Map{"value" → "java", "boost" → 2}
  //
  // Query-level example: {"query_string": {"query": "java"}}
  //   queryBody = Map{"query" → "java"}
  //   firstValue = "java" → instanceof Map = false
  //   → Add boost to queryBody: Map{"query" → "java", "boost" → 2}
  const firstValue = queryBody.values().next().value;

  if (firstValue instanceof Map) {
    // Field-level: boost goes inside the field params Map
    firstValue.set('boost', boostValue);
  } else {
    // Query-level: boost goes at the query body level
    queryBody.set('boost', boostValue);
  }

  return childResult;
};
