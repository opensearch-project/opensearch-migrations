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
 * Solr date-math bounds are translated to OpenSearch date-math via the
 * shared `translateSolrDateMath` helper, so e.g.
 *   `review_date:[NOW-365DAYS TO NOW]`
 *     → range{review_date: {gte: 'now-365d', lte: 'now'}}
 *
 * Examples:
 *   `price:[10 TO 100]`            → Map{"range" → Map{"price" → Map{"gte" → "10", "lte" → "100"}}}
 *   `price:{10 TO 100}`            → Map{"range" → Map{"price" → Map{"gt" → "10", "lt" → "100"}}}
 *   `price:[10 TO 100}`            → Map{"range" → Map{"price" → Map{"gte" → "10", "lt" → "100"}}}
 *   `price:[* TO 100]`             → Map{"range" → Map{"price" → Map{"lte" → "100"}}}
 *   `price:[10 TO *]`              → Map{"range" → Map{"price" → Map{"gte" → "10"}}}
 *   `d:[NOW-365DAYS TO NOW]`       → Map{"range" → Map{"d" → Map{"gte" → "now-365d", "lte" → "now"}}}
 *   `d:[2020-01-01T00:00:00Z TO *]`→ Map{"range" → Map{"d" → Map{"gte" → "2020-01-01T00:00:00Z"}}}
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';
import { translateSolrDateMath } from '../dateMath';

export const rangeRule: TransformRuleFn = (
  node: ASTNode,
  // Range is a leaf node — transformChild not used
  _transformChild,
): Map<string, any> => {
  const { field, lower, upper, lowerInclusive, upperInclusive } = node;

  // [* TO *] means "field exists" in Solr — convert to exists query
  if (lower === '*' && upper === '*') {
    return new Map([['exists', new Map([['field', field]])]]);
  }

  const bounds = new Map<string, string>();

  // Only include bounds that are not unbounded (*)
  if (lower !== '*') {
    bounds.set(lowerInclusive ? 'gte' : 'gt', translateSolrDateMath(lower));
  }
  if (upper !== '*') {
    bounds.set(upperInclusive ? 'lte' : 'lt', translateSolrDateMath(upper));
  }

  return new Map([['range', new Map([[field, bounds]])]]);
};
