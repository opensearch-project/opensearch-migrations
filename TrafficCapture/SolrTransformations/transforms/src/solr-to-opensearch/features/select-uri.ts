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

/** Solr query params this feature handles. */
export const params: string[] = [];

export const request: MicroTransform<RequestContext> = {
  name: 'select-uri',
  apply: (ctx) => {
    ctx.msg.set('URI', `/${ctx.collection}/_search`);
    ctx.msg.set('method', 'POST');
    const headers = ctx.msg.get('headers');
    if (headers) {
      // Remove original Content-Type (may differ in casing) to avoid duplicate headers.
      // The Java proxy uses headers.add() which would create two Content-Type entries.
      headers.delete('Content-Type');
      headers.set('content-type', 'application/json');
    }
  },
};
