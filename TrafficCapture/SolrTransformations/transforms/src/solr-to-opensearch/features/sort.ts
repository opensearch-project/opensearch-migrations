/**
 * Sort — translate Solr sort parameter to OpenSearch sort array.
 *
 * Parses comma-separated field/direction pairs from the `sort` parameter.
 * Maps Solr `score` field to OpenSearch `_score`.
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 *
 * Requirements: 12.7
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'sort',
  apply: (ctx) => {
    const sortParam = ctx.params.get('sort');
    if (!sortParam) return;

    const sortArray: Map<string, any>[] = [];
    const pairs = sortParam.split(',');

    for (const pair of pairs) {
      const trimmed = pair.trim();
      if (!trimmed) continue;

      const parts = trimmed.split(/\s+/);
      let field = parts[0];
      const direction = parts[1]?.toLowerCase() || 'asc';

      // Map Solr score → OpenSearch _score
      if (field === 'score') {
        field = '_score';
      }

      sortArray.push(new Map([[field, new Map([['order', direction]])]]));
    }

    if (sortArray.length > 0) {
      ctx.body.set('sort', sortArray);
    }
  },
};
