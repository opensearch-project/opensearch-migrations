/**
 * Boost query bq parameter — convert Solr bq params to OpenSearch bool.should.
 *
 * Solr's bq (boost query) parameter adds optional query clauses that
 * additively influence scoring without affecting which documents match.
 * Available in the eDisMax query parser only (defType=dismax is not supported).
 *
 * Multiple bq params are each added as separate optional (should) clauses.
 *
 * Mapping:
 *   Solr: q=cheese&defType=edismax&bq=category:food^10&bq=category:deli^5
 *   OpenSearch: {
 *     "query": {
 *       "bool": {
 *         "must": [<translated q>],
 *         "should": [<translated bq1>, <translated bq2>]
 *       }
 *     }
 *   }
 *
 * This transform runs AFTER filter-query-fq. If the existing query is already
 * a bool query (from query-q or fq), we add should clauses directly to avoid
 * unnecessary nesting.
 *
 * bq values are parsed through translateQ() — the same query engine used for
 * q and fq. Local params syntax ({!...}) in bq values is rejected at
 * validation time to prevent silent lossy translation.
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';
import type { ParamRule } from './validation';
import { translateQ } from '../query-engine/orchestrator/translateQ';

/** Solr query params this feature handles. */
export const params = ['bq'];
export const paramRules: ParamRule[] = [
  { name: 'bq', type: 'rejectPattern', pattern: String.raw`^\{!`, reason: 'Local params ({!...}) syntax in bq is not supported' },
];

const DISMAX_TYPES = new Set(['edismax']);

/**
 * Parse a single bq value using the same query engine as q and fq.
 */
function parseBq(bq: string): Map<string, any> {
  return translateQ(new Map([['q', bq]])).dsl;
}

/**
 * Check if a query is a bool query (has 'bool' as its only key).
 */
function isBoolQuery(query: Map<string, any>): boolean {
  return query.size === 1 && query.has('bool');
}

export const request: MicroTransform<RequestContext> = {
  name: 'boost-query-bq',
  match: (ctx) => ctx.params.has('bq'),
  apply: (ctx) => {
    const defType = ctx.params.get('defType') ?? '';
    if (!DISMAX_TYPES.has(defType)) {
      throw new Error(
        `[boost-query-bq] bq parameter requires defType=edismax, got '${defType || '(none)'}'`,
      );
    }

    const bqValues = ctx.params.getAll('bq').filter((v) => v.trim() !== '');
    if (bqValues.length === 0) return;

    if (bqValues.some((v) => /\^-\d/.test(v))) {
      throw new Error(
        '[boost-query-bq] Negative boost in bq (e.g., field:value^-N) is not supported — ' +
        'Solr uses it for score reduction, but OpenSearch has no equivalent additive negative boost',
      );
    }

    const shouldClauses = bqValues.map(parseBq);

    const existingQuery = ctx.body.get('query');

    if (existingQuery && isBoolQuery(existingQuery)) {
      const existingBool = existingQuery.get('bool') as Map<string, any>;
      const existingShould = existingBool.get('should') || [];
      existingBool.set('should', [...existingShould, ...shouldClauses]);
    } else {
      const boolQuery = new Map<string, any>();
      boolQuery.set('must', [existingQuery ?? new Map([['match_all', new Map()]])]);
      boolQuery.set('should', shouldClauses);
      ctx.body.set('query', new Map([['bool', boolQuery]]));
    }
  },
};
