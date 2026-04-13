/**
 * Solrconfig defaults — apply requestHandler defaults, invariants, and appends
 * from solrconfig.xml.
 *
 * These three categories are defined by the Solr spec:
 * @see https://solr.apache.org/guide/solr/latest/configuration-guide/requesthandlers-searchcomponents.html
 *
 * Per request:
 *   - defaults: set param only if NOT already in the request
 *   - invariants: always override, regardless of request params
 *   - appends: add alongside existing values (e.g., multiple fq)
 *
 * Config is read from ctx.solrConfig (set per-request from bindings in request.transform.ts).
 * Must run FIRST in the select pipeline so all downstream transforms see merged params.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'solrconfig-defaults',
  match: (ctx) => ctx.solrConfig != null,
  apply: (ctx) => {
    const handler = ctx.solrConfig?.['/' + ctx.endpoint];
    if (!handler) return;

    if (handler.defaults) {
      for (const key in handler.defaults) {
        if (!ctx.params.has(key)) {
          ctx.params.set(key, handler.defaults[key]);
        }
      }
    }

    if (handler.invariants) {
      for (const key in handler.invariants) {
        ctx.params.set(key, handler.invariants[key]);
      }
    }

    if (handler.appends) {
      for (const key in handler.appends) {
        ctx.params.append(key, handler.appends[key]);
      }
    }
  },
};
