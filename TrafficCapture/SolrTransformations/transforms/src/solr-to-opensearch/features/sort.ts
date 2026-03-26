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

function parseSortClause(clause: string): Map<string, any> {
  const parts = clause.trim().split(/\s+/);
  if (parts.length < 2 || !parts[0]) {
    const msg = `Invalid sort clause '${clause}': must specify 'asc' or 'desc' direction`;
    console.error(`[sort] ${msg}`);
    throw new Error(msg);
  }

  const field = parts[0];
  const order = parts[1].toLowerCase();

  if (order !== 'asc' && order !== 'desc') {
    const msg = `Invalid sort direction '${parts[1]}' in '${clause}': must be 'asc' or 'desc'`;
    console.error(`[sort] ${msg}`);
    throw new Error(msg);
  }

  // score maps to _score in OpenSearch
  if (field === 'score') {
    return new Map([['_score', new Map([['order', order]])]]);
  }

  return new Map([[field, new Map([['order', order]])]]);
}

function parseSort(sort: string | null): Array<Map<string, any>> | null {
  if (!sort?.trim()) return null;

  // Detect function-based sorting before splitting (e.g., div(popularity,price), field(name,min))
  if (sort.includes('(')) {
    const msg = `Unsupported sort '${sort}': function-based sorting is not supported`;
    console.error(`[sort] ${msg}`);
    throw new Error(msg);
  }

  const clauses = sort.split(',').map((c) => c.trim()).filter(Boolean);
  if (clauses.length === 0) {
    console.warn(`[sort] Empty sort clauses after parsing: '${sort}'`);
    return null;
  }

  return clauses.map(parseSortClause);
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
