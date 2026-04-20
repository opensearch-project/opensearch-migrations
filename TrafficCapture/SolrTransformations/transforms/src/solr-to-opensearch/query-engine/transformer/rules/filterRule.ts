/**
 * Transformation rule for FilterNode → OpenSearch bool.filter query.
 *
 * Solr's filter(...) syntax wraps a sub-query to indicate it should be:
 * 1. Cached in the filter cache for reuse
 * 2. Executed as a constant-score (non-scoring) clause
 *
 * In OpenSearch, the equivalent is wrapping the clause in bool.filter,
 * which also executes without scoring and benefits from query caching.
 *
 * Optimization: If the child is already a bool query, we add the filter
 * clause directly to avoid unnecessary nesting (similar to filter-query-fq.ts).
 *
 * Examples:
 *   Solr:       filter(inStock:true)
 *   OpenSearch: { "bool": { "filter": [{ "match": { "inStock": { "query": "true" } } }] } }
 *
 *   Solr:       q=features:songs OR filter(inStock:true)
 *   OpenSearch: { "bool": { "should": [
 *                  { "match": { "features": { "query": "songs" } } },
 *                  { "bool": { "filter": [{ "match": { "inStock": { "query": "true" } } }] } }
 *                ] } }
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn, TransformChild } from '../types';

/**
 * Check if a query is a bool query (has 'bool' as its only key).
 */
function isBoolQuery(query: Map<string, any>): boolean {
  return query.size === 1 && query.has('bool');
}

export const filterRule: TransformRuleFn = (
  node: ASTNode,
  transformChild: TransformChild,
): Map<string, any> => {
  // Transform the child node
  const childResult = transformChild(node.child);

  // Optimization: If child is already a bool query with only a filter clause,
  // return it directly to avoid double-wrapping { bool: { filter: [{ bool: { filter: [...] } }] } }
  if (isBoolQuery(childResult)) {
    const childBool = childResult.get('bool') as Map<string, any>;
    // If child bool has only 'filter' key, it's already a filter-only bool - return as-is
    if (childBool.size === 1 && childBool.has('filter')) {
      return childResult;
    }
  }

  // Wrap in bool.filter for non-scoring execution
  // OpenSearch's bool.filter array expects query clauses
  const boolQuery = new Map<string, any>([
    ['filter', [childResult]],
  ]);

  return new Map([['bool', boolQuery]]);
};
