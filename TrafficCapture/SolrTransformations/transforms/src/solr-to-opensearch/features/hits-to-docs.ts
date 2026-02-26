/**
 * Hits to docs — convert OpenSearch hits.hits[]._source to Solr response.docs[].
 *
 * Response-only.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext } from '../context';

interface OpenSearchHit {
  _source: Record<string, unknown>;
}

export const response: MicroTransform<ResponseContext> = {
  name: 'hits-to-docs',
  match: (ctx) => !!(ctx.responseBody as any).hits,
  apply: (ctx) => {
    const hits = (ctx.responseBody as any).hits;
    const docs = hits.hits.map((hit: OpenSearchHit) => hit._source);
    ctx.responseBody.response = {
      numFound: hits.total.value,
      start: 0,
      numFoundExact: true,
      docs,
    };
    delete ctx.responseBody.hits;
    delete ctx.responseBody.took;
    delete ctx.responseBody.timed_out;
    delete ctx.responseBody._shards;
  },
};
