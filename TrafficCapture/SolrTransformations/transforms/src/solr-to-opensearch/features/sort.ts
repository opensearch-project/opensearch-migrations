/**
 * Sort — Solr sort param → OpenSearch sort clause.
 *
 * Solr: sort=field1 asc, field2 desc
 * OpenSearch: sort: [{field1: {order: "asc"}}, {field2: {order: "desc"}}]
 *
 * Special case: sort=score desc → _score in OpenSearch.
 *
 * Request-only.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'sort',
  match: (ctx) => ctx.params.has('sort'),
  apply: (ctx) => {
    const sortParam = ctx.params.get('sort')!;
    const clauses = sortParam.split(',').map(s => s.trim()).filter(Boolean);
    const sort: Record<string, unknown>[] = [];

    for (const clause of clauses) {
      const parts = clause.split(/\s+/);
      const field = parts[0] === 'score' ? '_score' : parts[0];
      const order = (parts[1] || 'asc').toLowerCase();
      sort.push({ [field]: { order } });
    }

    if (sort.length > 0) ctx.body.sort = sort;
  },
};
