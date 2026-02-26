/**
 * Select URI rewrite — /solr/{collection}/select → /{collection}/_search
 *
 * Request-only. Must run first in the select pipeline since other
 * transforms depend on the rewritten URI and method.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'select-uri',
  apply: (ctx) => {
    ctx.msg.URI = `/${ctx.collection}/_search`;
    ctx.msg.method = 'POST';
    ctx.msg.headers = { ...ctx.msg.headers, 'content-type': 'application/json' };
  },
};
