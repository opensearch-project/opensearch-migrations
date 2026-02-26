/**
 * Hits to docs — convert OpenSearch hits.hits[]._source to Solr response.docs[].
 *
 * Response-only. Uses .get()/.set() on Java Maps throughout.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext, JavaMap } from '../context';

export const response: MicroTransform<ResponseContext> = {
  name: 'hits-to-docs',
  match: (ctx) => ctx.responseBody.has('hits'),
  apply: (ctx) => {
    const hits: JavaMap = ctx.responseBody.get('hits');
    const hitsArray: JavaMap[] = hits.get('hits');
    const total: JavaMap = hits.get('total');

    const docs: JavaMap[] = [];
    for (let i = 0; i < hitsArray.length; i++) {
      const hit = hitsArray[i];
      const source: JavaMap = hit.get('_source');
      const doc = new Map();
      for (const key of source.keys()) {
        const value = source.get(key);
        // Solr wraps multi-valued field values in arrays; id is single-valued
        doc.set(key, key === 'id' || Array.isArray(value) ? value : [value]);
      }
      // Solr adds _version_ (optimistic concurrency) to every doc
      doc.set('_version_', hit.has('_version') ? hit.get('_version') : 0);
      docs.push(doc);
    }

    const responseMap = new Map();
    responseMap.set('numFound', total.get('value'));
    responseMap.set('start', 0);
    responseMap.set('numFoundExact', true);
    responseMap.set('docs', docs);
    ctx.responseBody.set('response', responseMap);

    ctx.responseBody.delete('hits');
    ctx.responseBody.delete('took');
    ctx.responseBody.delete('timed_out');
    ctx.responseBody.delete('_shards');
  },
};
