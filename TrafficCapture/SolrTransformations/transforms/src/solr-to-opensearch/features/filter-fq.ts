/**
 * Filter query (fq) — translate Solr fq params to OpenSearch bool.filter clauses.
 *
 * Each fq value is parsed through translateQ independently. The resulting DSL
 * clauses are placed in bool.filter context to preserve non-scoring semantics.
 * Any boost keys are stripped from filter clauses since filters don't affect scoring.
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 *
 * Requirements: 12.3, 13.4
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';
import { translateQ } from '../translator/translateQ';

/**
 * Recursively strip 'boost' keys from a DSL Map structure.
 * Filter clauses must not contain scoring-related keys.
 */
function stripBoost(dsl: any): any {
  if (!(dsl instanceof Map)) return dsl;
  const result = new Map<string, any>();
  for (const [key, value] of dsl.entries()) {
    if (key === 'boost') continue;
    if (value instanceof Map) {
      result.set(key, stripBoost(value));
    } else if (Array.isArray(value)) {
      result.set(key, value.map((item) => stripBoost(item)));
    } else {
      result.set(key, value);
    }
  }
  return result;
}

export const request: MicroTransform<RequestContext> = {
  name: 'filter-fq',
  apply: (ctx) => {
    const fqValues = ctx.params.getAll('fq');
    if (fqValues.length === 0) return;

    const filterClauses: Map<string, any>[] = [];
    for (const fq of fqValues) {
      const result = translateQ({ q: fq });
      filterClauses.push(stripBoost(result.dsl));
    }

    // Wrap existing query + filter clauses in bool.filter structure
    const existingQuery = ctx.body.get('query');
    const boolMap = new Map<string, any>();

    if (existingQuery) {
      boolMap.set('must', [existingQuery]);
    }
    boolMap.set('filter', filterClauses);

    ctx.body.set('query', new Map([['bool', boolMap]]));
  },
};
