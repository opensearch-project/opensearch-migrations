/**
 * Sort parameter — convert Solr sort param to OpenSearch sort DSL.
 *
 * Handles:
 *   - sort=field asc/desc → sort: [{ field: "asc" }]
 *   - sort=field1 desc, field2 asc → sort: [{ field1: "desc" }, { field2: "asc" }]
 *   - sort=score desc → sort: ["_score"]
 *   - Absent sort → no sort clause (OpenSearch defaults to _score desc)
 *
 * Request-only. All output is Maps/arrays for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

function parseSortClause(clause: string): Map<string, any> | string | null {
  const parts = clause.trim().split(/\s+/);
  if (parts.length < 1 || !parts[0]) return null;

  const field = parts[0];
  const order = parts[1]?.toLowerCase() || 'asc';

  // score maps to _score in OpenSearch
  if (field === 'score') {
    return order === 'desc' ? '_score' : new Map([['_score', new Map([['order', order]])]]);
  }

  return new Map([[field, new Map([['order', order]])]]);
}

function parseSort(sort: string | null): Array<Map<string, any> | string> | null {
  if (!sort?.trim()) return null;

  const clauses = sort.split(',').map((c) => c.trim()).filter(Boolean);
  const result: Array<Map<string, string> | string> = [];

  for (const clause of clauses) {
    const parsed = parseSortClause(clause);
    if (parsed) result.push(parsed);
  }

  return result.length > 0 ? result : null;
}

export const request: MicroTransform<RequestContext> = {
  name: 'sort',
  apply: (ctx) => {
    const sort = ctx.params.get('sort');
    const sortClauses = parseSort(sort);
    if (sortClauses) {
      ctx.body.set('sort', sortClauses);
    }
  },
};
