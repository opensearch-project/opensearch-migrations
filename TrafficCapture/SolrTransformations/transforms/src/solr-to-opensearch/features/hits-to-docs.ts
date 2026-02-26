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
    const docs = hits.hits.map((hit: OpenSearchHit) => {
      const doc: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(hit._source)) {
        // Solr wraps multi-valued field values in arrays; id is single-valued
        doc[key] = key === 'id' || Array.isArray(value) ? value : [value];
      }
      return doc;
    });
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
