/**
 * Pagination — Solr start/rows → OpenSearch from/size.
 *
 * Request: start=10&rows=20 → from: 10, size: 20
 * Response: set response.start from the original request param.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'pagination',
  match: (ctx) => ctx.params.has('start') || ctx.params.has('rows'),
  apply: (ctx) => {
    const start = ctx.params.get('start');
    const rows = ctx.params.get('rows');
    if (start) ctx.body.from = parseInt(start, 10);
    if (rows) ctx.body.size = parseInt(rows, 10);
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'pagination',
  match: (ctx) => {
    const start = ctx.requestParams.get('start');
    return !!start && !!(ctx.responseBody.response as any);
  },
  apply: (ctx) => {
    const start = parseInt(ctx.requestParams.get('start')!, 10);
    (ctx.responseBody.response as any).start = start;
  },
};
