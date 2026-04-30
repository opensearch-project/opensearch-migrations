/**
 * Query q parameter — convert Solr q param to OpenSearch query DSL.
 *
 * Handles:
 *   - *:* → match_all
 *   - field:value → term query
 *   - Anything else → query_string passthrough (lets OpenSearch parse it)
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, JavaMap } from '../context';
import type { ParamRule } from './validation';
import { translateQ } from '../query-engine/orchestrator/translateQ';

/** Solr query params this feature handles. */
export const params = ['q', 'rows', 'start', 'q.op', 'defType', 'qf', 'pf', 'ps', 'tie'];
export const paramRules: ParamRule[] = [
  { name: 'rows', type: 'integer' },
  { name: 'start', type: 'integer' },
  { name: 'q', type: 'rejectPattern', pattern: String.raw`^\{!`, reason: 'Local params ({!...}) syntax in q is not supported' },
];

export function parseSolrQuery(q: string): JavaMap {
  if (!q || q === '*:*') return new Map([['match_all', new Map()]]);

  const fieldMatch = /^([^:]+):(.+)$/.exec(q);
  if (fieldMatch) {
    const [, field, value] = fieldMatch;
    if (field === '*' && value === '*') return new Map([['match_all', new Map()]]);
    return new Map([['term', new Map([[field, value]])]]);
  }

  return new Map([['query_string', new Map([['query', q]])]]);
}

/** Convert URLSearchParams to a Map for translateQ. */
function paramsToMap(params: URLSearchParams): Map<string, string> {
  const map = new Map<string, string>();
  for (const [key, value] of params.entries()) {
    map.set(key, value);
  }
  return map;
}

export const request: MicroTransform<RequestContext> = {
  name: 'query-q',
  apply: (ctx) => {
    const result = translateQ(paramsToMap(ctx.params), 'fail-fast', ctx.fieldTypes);
    ctx.body.set('query', result.dsl);

    // TODO: expose result.warnings to caller for observability

    // rows → size, start → from
    const rows = ctx.params.get('rows');
    if (rows) ctx.body.set('size', Number.parseInt(rows, 10));
    const start = ctx.params.get('start');
    if (start) ctx.body.set('from', Number.parseInt(start, 10));
  },
};
