/**
 * Pagination — translate Solr start/rows to OpenSearch from/size.
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 *
 * Requirements: 12.8
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'pagination',
  apply: (ctx) => {
    const startParam = ctx.params.get('start');
    const rowsParam = ctx.params.get('rows');

    if (startParam != null) {
      const from = parseInt(startParam, 10);
      if (!isNaN(from)) {
        ctx.body.set('from', from);
      }
    }

    if (rowsParam != null) {
      const size = parseInt(rowsParam, 10);
      if (!isNaN(size)) {
        ctx.body.set('size', size);
      }
    }
  },
};
