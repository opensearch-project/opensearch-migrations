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

function parseSolrQuery(q: string): JavaMap {
  if (!q || q === '*:*') return new Map([['match_all', new Map()]]);

  const fieldMatch = /^([^:]+):(.+)$/.exec(q);
  if (fieldMatch) {
    const [, field, value] = fieldMatch;
    if (field === '*' && value === '*') return new Map([['match_all', new Map()]]);
    return new Map([['term', new Map([[field, value]])]]);
  }

  return new Map([['query_string', new Map([['query', q]])]]);
}

export const request: MicroTransform<RequestContext> = {
  name: 'query-q',
  apply: (ctx) => {
    const q = ctx.params.get('q') || '*:*';
    ctx.body.set('query', parseSolrQuery(q));
  },
};
