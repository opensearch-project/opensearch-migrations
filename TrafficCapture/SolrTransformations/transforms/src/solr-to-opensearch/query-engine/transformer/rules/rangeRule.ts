/**
 * Transformation rule for RangeNode → OpenSearch `range` query.
 *
 * Maps Solr's range syntax to OpenSearch's range query:
 *   RangeNode.lowerInclusive=true  → gte (greater than or equal)
 *   RangeNode.lowerInclusive=false → gt  (greater than)
 *   RangeNode.upperInclusive=true  → lte (less than or equal)
 *   RangeNode.upperInclusive=false → lt  (less than)
 *
 * Unbounded ranges use `*` which is omitted from the output.
 *
 * Examples:
 *   `price:[10 TO 100]` → Map{"range" → Map{"price" → Map{"gte" → "10", "lte" → "100"}}}
 *   `price:{10 TO 100}` → Map{"range" → Map{"price" → Map{"gt" → "10", "lt" → "100"}}}
 *   `price:[10 TO 100}` → Map{"range" → Map{"price" → Map{"gte" → "10", "lt" → "100"}}}
 *   `price:[* TO 100]`  → Map{"range" → Map{"price" → Map{"lte" → "100"}}}
 *   `price:[10 TO *]`   → Map{"range" → Map{"price" → Map{"gte" → "10"}}}
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';

export const rangeRule: TransformRuleFn = (
  node: ASTNode,
  // Range is a leaf node — transformChild not used
  _transformChild,
): Map<string, any> => {
  if (node.type !== 'range') {
    console.error(`rangeRule called with wrong node type: ${node.type}`);
    throw new Error(`rangeRule called with wrong node type: ${node.type}`);
  }

  const { field, lower, upper, lowerInclusive, upperInclusive } = node;

  // [* TO *] means "field exists" in Solr — convert to exists query
  if (lower === '*' && upper === '*') {
    return new Map([['exists', new Map([['field', field]])]]);
  }

  const bounds = new Map<string, string>();

  // Only include bounds that are not unbounded (*)
  if (lower !== '*') {
    bounds.set(lowerInclusive ? 'gte' : 'gt', lower);
  }
  if (upper !== '*') {
    bounds.set(upperInclusive ? 'lte' : 'lt', upper);
  }

  return new Map([['range', new Map([[field, bounds]])]]);
};
