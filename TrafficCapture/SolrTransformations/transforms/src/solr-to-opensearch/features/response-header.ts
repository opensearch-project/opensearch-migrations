/**
 * Response header — synthesize Solr responseHeader.
 *
 * Response-only. Uses .set() and new Map() for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext } from '../context';

export const response: MicroTransform<ResponseContext> = {
  name: 'response-header',
  apply: (ctx) => {
    ctx.responseBody.set('responseHeader', new Map([['status', 0], ['QTime', 0]]));
  },
};
