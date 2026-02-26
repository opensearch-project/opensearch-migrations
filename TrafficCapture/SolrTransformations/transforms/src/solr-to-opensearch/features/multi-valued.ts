/**
 * Multi-valued field wrapping — wrap scalar string values in arrays
 * to match Solr's multi-valued field format.
 *
 * Response-only. Runs after hits-to-docs.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext } from '../context';

function wrapMultiValued(doc: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(doc)) {
    if (key === 'id') {
      result[key] = val;
    } else {
      result[key] = typeof val === 'string' ? [val] : val;
    }
  }
  return result;
}

export const response: MicroTransform<ResponseContext> = {
  name: 'multi-valued',
  match: (ctx) => !!(ctx.responseBody.response as any)?.docs,
  apply: (ctx) => {
    const resp = ctx.responseBody.response as any;
    resp.docs = resp.docs.map(wrapMultiValued);
  },
};
