/**
 * Response header — synthesize Solr responseHeader.
 *
 * Response-only.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext } from '../context';

export const response: MicroTransform<ResponseContext> = {
  name: 'response-header',
  apply: (ctx) => {
    ctx.responseBody.responseHeader = { status: 0, QTime: 0 };
  },
};
