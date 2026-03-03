/**
 * Select URI rewrite — /solr/{collection}/select → /{collection}/_search
 *
 * Request-only. Must run first in the select pipeline since other
 * transforms depend on the rewritten URI and method.
 *
 * Uses Java Map .get()/.set() for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'select-uri',
  apply: (ctx) => {
    ctx.msg.set('URI', `/${ctx.collection}/_search`);
    ctx.msg.set('method', 'POST');
    const headers = ctx.msg.get('headers');
    if (headers) {
      headers.set('content-type', 'application/json');
    }
  },
};
