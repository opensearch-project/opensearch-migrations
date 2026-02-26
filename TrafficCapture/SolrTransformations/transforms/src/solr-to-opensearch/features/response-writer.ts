/**
 * Response writer (wt) — handle Solr wt param.
 *
 * Solr supports wt=json, wt=xml, wt=csv, etc. The proxy always communicates
 * with OpenSearch via JSON. This transform ensures the response content-type
 * matches what the Solr client expects.
 *
 * For now, only wt=json is fully supported. Other values pass through with
 * a warning in the response header.
 *
 * Response-only.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext } from '../context';

export const response: MicroTransform<ResponseContext> = {
  name: 'response-writer',
  match: (ctx) => {
    const wt = ctx.requestParams.get('wt');
    return !!wt && wt === 'json';
  },
  apply: (ctx) => {
    // Ensure response content-type is application/json for wt=json
    if (ctx.response.headers) {
      ctx.response.headers['content-type'] = 'application/json;charset=utf-8';
    }
  },
};
