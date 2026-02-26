/**
 * Filter query (fq) — Solr fq params → OpenSearch bool filter clauses.
 *
 * Solr supports multiple fq params, each adding a filter clause.
 * Maps to: bool.filter[] in the OpenSearch query.
 *
 * Request-only. Reuses the query parser from query-q for fq value parsing.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

/** Minimal Solr fq parser — handles field:value, field:"phrase", field:[range]. */
function parseFqClause(fq: string): Record<string, unknown> {
  const colonIdx = fq.indexOf(':');
  if (colonIdx < 0) return { query_string: { query: fq } };

  const field = fq.slice(0, colonIdx);
  const value = fq.slice(colonIdx + 1);

  // range: [lo TO hi]
  const rangeMatch = value.match(/^[\[{](.+?)\s+TO\s+(.+?)[\]}]$/);
  if (rangeMatch) {
    const range: Record<string, unknown> = {};
    const loInc = value[0] === '[';
    const hiInc = value[value.length - 1] === ']';
    if (rangeMatch[1] !== '*') range[loInc ? 'gte' : 'gt'] = rangeMatch[1];
    if (rangeMatch[2] !== '*') range[hiInc ? 'lte' : 'lt'] = rangeMatch[2];
    return { range: { [field]: range } };
  }

  // phrase: "value"
  if (value.startsWith('"') && value.endsWith('"')) {
    return { match_phrase: { [field]: value.slice(1, -1) } };
  }

  // wildcard
  if (value.includes('*') || value.includes('?')) {
    return { wildcard: { [field]: { value } } };
  }

  // negation: -field:value or NOT handled at fq level
  if (value === '*:*' || (field === '*' && value === '*')) return { match_all: {} };

  return { term: { [field]: value } };
}

export const request: MicroTransform<RequestContext> = {
  name: 'filter-query',
  match: (ctx) => ctx.params.has('fq'),
  apply: (ctx) => {
    const fqValues = ctx.params.getAll('fq');
    const filters = fqValues.map(parseFqClause);

    // Wrap existing query in bool.must, add filters to bool.filter
    const existingQuery = ctx.body.query || { match_all: {} };
    ctx.body.query = {
      bool: {
        must: [existingQuery],
        filter: filters,
      },
    };
  },
};
