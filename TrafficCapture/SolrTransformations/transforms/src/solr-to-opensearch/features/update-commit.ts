/**
 * Commit handler — {"commit":{}} → POST /{collection}/_refresh
 *
 * Solr's commit makes documents searchable. OpenSearch's _refresh is the
 * closest equivalent — it forces a refresh of the index, making all
 * recently indexed documents available for search.
 *
 * softCommit vs hardCommit: OpenSearch has no distinction. Both map to _refresh.
 * See LIMITATIONS shortcode COMMIT-SOFTCOMMIT.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'update-commit',
  apply: (ctx) => {
    ctx.msg.set('URI', `/${ctx.collection}/_refresh`);
    ctx.msg.set('method', 'POST');
    // _refresh takes no body — clear it
    ctx.body = new Map();
  },
};

/**
 * Response: _refresh returns {_shards:{total,successful,failed}}
 * → Solr {responseHeader:{status:0, QTime:0}}
 */
export function isRefreshResponse(body: any): boolean {
  return body != null && typeof body.has === 'function'
    && body.has('_shards') && !body.has('result') && !body.has('items')
    && !body.has('deleted');
}

export const response: MicroTransform<ResponseContext> = {
  name: 'update-commit-response',
  match: (ctx) => isRefreshResponse(ctx.responseBody),
  apply: (ctx) => {
    const body = ctx.responseBody;
    const shards = body.get('_shards');
    const failed = shards && typeof shards.get === 'function'
      ? (typeof shards.get('failed') === 'number' && shards.get('failed') > 0)
      : false;

    const keys = Array.from(body.keys());
    for (const key of keys) {
      body.delete(key);
    }

    body.set(
      'responseHeader',
      new Map<string, unknown>([
        ['status', failed ? 1 : 0],
        ['QTime', 0],
      ]),
    );
  },
};
