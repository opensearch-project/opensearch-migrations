/**
 * Query q parameter — convert Solr q param to OpenSearch query DSL.
 *
 * Handles:
 *   - *:* → match_all
 *   - field:value → term query
 *   - Anything else → query_string passthrough (lets OpenSearch parse it)
 *
 * Request-only.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

function parseSolrQuery(q: string): Record<string, unknown> {
  if (!q || q === '*:*') return { match_all: {} };

  // field:value → term query
  const fieldMatch = /^([^:]+):(.+)$/.exec(q);
  if (fieldMatch) {
    const [, field, value] = fieldMatch;
    if (field === '*' && value === '*') return { match_all: {} };
    return { term: { [field]: value } };
  }

  // Fallback: let OpenSearch parse it
  return { query_string: { query: q } };
}

export const request: MicroTransform<RequestContext> = {
  name: 'query-q',
  apply: (ctx) => {
    const q = ctx.params.get('q') || '*:*';
    ctx.body.query = parseSolrQuery(q);
  },
};
