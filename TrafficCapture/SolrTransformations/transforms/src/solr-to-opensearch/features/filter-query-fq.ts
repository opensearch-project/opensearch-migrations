/**
 * Filter query fq parameter — convert Solr fq params to OpenSearch bool.filter.
 *
 * Solr's fq (filter query) parameter restricts results without affecting scores.
 * Multiple fq params are AND'd together. Each fq uses the same query syntax as q.
 *
 * Mapping:
 *   Solr: q=title:java&fq=status:published&fq=price:[10 TO 100]
 *   OpenSearch: {
 *     "query": {
 *       "bool": {
 *         "must": [<translated q>],
 *         "filter": [<translated fq1>, <translated fq2>]
 *       }
 *     }
 *   }
 *
 * This transform runs AFTER query-q, wrapping the existing query in a bool.must
 * and adding filter clauses to bool.filter. If the existing query is already a
 * bool query, we add filters directly to avoid unnecessary nesting.
 *
 * Design note: Pure negation fq values (e.g., fq=-status:deleted) could be lifted
 * to top-level must_not instead of being wrapped in filter, producing flatter queries.
 * This optimization was analyzed but not implemented because:
 * - must_not is always non-scoring in OpenSearch regardless of placement
 * - The current nested structure is functionally correct with identical results
 * - The optimization adds complexity to the transform logic for marginal benefit
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';
import type { ParamRule } from './validation';
import { translateQ } from '../query-engine/orchestrator/translateQ';

/** Solr query params this feature handles. */
export const params = ['fq'];
export const paramRules: ParamRule[] = [
  { name: 'fq', type: 'rejectPattern', pattern: String.raw`^\{!.*\bcache\b`, reason: 'Filter query cache local param ({!cache=...}) is not supported - OpenSearch manages filter caching internally' },
  { name: 'fq', type: 'rejectPattern', pattern: String.raw`^\{!.*\bcost\b`, reason: 'Filter query cost local param ({!cost=...}) is not supported - OpenSearch does not support filter evaluation ordering hints' },
  { name: 'fq', type: 'rejectPattern', pattern: String.raw`^\{!frange\b`, reason: 'Function range query ({!frange}) in fq is not supported' },
  { name: 'fq', type: 'rejectPattern', pattern: String.raw`^\{!geofilt\b`, reason: 'Geospatial filter ({!geofilt}) in fq is not supported' },
];

/**
 * Parse a single fq value using the same query engine as q.
 * Creates a params map with the fq value as 'q' for translateQ.
 */
function parseFq(fq: string): Map<string, any> {
  const params = new Map<string, string>([['q', fq]]);
  const result = translateQ(params);
  return result.dsl;
}

/**
 * Check if a query is a bool query (has 'bool' as its only key).
 */
function isBoolQuery(query: Map<string, any>): boolean {
  return query.size === 1 && query.has('bool');
}

//TODO: Fix bug fq=+A +B and add tests in follow up PR
export const request: MicroTransform<RequestContext> = {
  name: 'filter-query-fq',
  match: (ctx) => ctx.params.has('fq'),
  apply: (ctx) => {
    // Get all fq values (can be multiple), filtering out empty/whitespace-only values
    const fqValues = ctx.params.getAll('fq').filter((v) => v.trim() !== '');
    if (fqValues.length === 0) return;

    // Parse each fq into OpenSearch DSL
    const filters = fqValues.map(parseFq);

    // Get the existing query (set by query-q transform)
    const existingQuery = ctx.body.get('query');

    if (existingQuery && isBoolQuery(existingQuery)) {
      // Flatten: add filters directly to existing bool query
      const existingBool = existingQuery.get('bool') as Map<string, any>;
      const existingFilters = existingBool.get('filter') || [];
      existingBool.set('filter', [...existingFilters, ...filters]);
    } else {
      // Create new bool query, wrapping existing query in must if present
      const boolQuery = new Map<string, any>();
      if (existingQuery) {
        boolQuery.set('must', [existingQuery]);
      }
      boolQuery.set('filter', filters);
      ctx.body.set('query', new Map([['bool', boolQuery]]));
    }
  },
};
