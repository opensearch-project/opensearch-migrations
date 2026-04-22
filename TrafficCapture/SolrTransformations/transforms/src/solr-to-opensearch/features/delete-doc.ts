/**
 * Single document delete by ID — DELETE /{collection}/_doc/{id}
 *
 * Called by update-router.ts after extracting the delete command data.
 * Expects ctx.body to be the normalized delete data: {"id": "..."}
 *
 * Solr:       POST /solr/{collection}/update  {"delete":{"id":"1"}}
 * OpenSearch:  DELETE /{collection}/_doc/1
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'delete-doc',
  apply: (ctx) => {
    if (!ctx.collection) {
      throw new Error('[delete-doc] delete: collection could not be determined from URI');
    }

    const rawId = ctx.body.get('id');
    const id = rawId != null ? String(rawId).trim() : '';
    if (!id) {
      throw new Error('[delete-doc] delete: document must have a non-empty "id" field');
    }

    const targetParams = new URLSearchParams();
    if (ctx.params.get('commit') === 'true' || ctx.params.has('commitWithin')) {
      targetParams.set('refresh', 'true');
    }

    const query = targetParams.toString();
    const suffix = query ? '?' + query : '';
    ctx.msg.set('URI', `/${ctx.collection}/_doc/${encodeURIComponent(id)}${suffix}`);
    ctx.msg.set('method', 'DELETE');
  },
};
