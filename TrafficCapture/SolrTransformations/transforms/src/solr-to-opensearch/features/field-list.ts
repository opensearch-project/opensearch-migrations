/**
 * Field list (fl) — translate Solr fl parameter to OpenSearch _source.
 *
 * Parses comma-separated field names from the `fl` parameter and sets
 * them as the `_source` array on the request body.
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 *
 * Requirements: 12.1
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'field-list',
  apply: (ctx) => {
    const fl = ctx.params.get('fl');
    if (!fl) return;

    const fields = fl
      .split(',')
      .map((f) => f.trim())
      .filter(Boolean);

    if (fields.length > 0) {
      ctx.body.set('_source', fields);
    }
  },
};
