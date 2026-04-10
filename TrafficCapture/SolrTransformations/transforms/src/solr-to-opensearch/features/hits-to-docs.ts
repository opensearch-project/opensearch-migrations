/**
 * Hits to docs — convert OpenSearch hits.hits[]._source to Solr response.docs[].
 *
 * Response-only. Uses .get()/.set() on Java Maps throughout.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext, JavaMap } from '../context';

/** Convert a single OpenSearch hit to a Solr doc. */
function hitToDoc(hit: JavaMap): JavaMap {
  const source: JavaMap = hit.get('_source');
  const doc = new Map();
  doc.set('id', hit.get('_id'));
  for (const key of source.keys()) {
    if (key === 'id') continue;
    const value = source.get(key);
    // Solr field type behavior:
    // - text_general: by default returns arrays, even for single values
    // - pint, pfloat, plong, pdouble (numeric): returns scalar values
    // - string (keyword): returns scalar values
    // - boolean: returns scalar values
    if (typeof value === 'number' || typeof value === 'boolean') {
      doc.set(key, value);
    } else if (Array.isArray(value)) {
      doc.set(key, value);
    } else {
      doc.set(key, [value]);
    }
  }
  doc.set('_version_', hit.has('_version') ? hit.get('_version') : 0);
  return doc;
}

export const response: MicroTransform<ResponseContext> = {
  name: 'hits-to-docs',
  match: (ctx) => ctx.responseBody.has('hits'),
  apply: (ctx) => {
    const hits: JavaMap = ctx.responseBody.get('hits');
    const hitsArray: JavaMap[] = hits.get('hits');
    const total: JavaMap = hits.get('total');

    const responseMap = new Map();
    responseMap.set('numFound', total.get('value'));
    responseMap.set('start', ctx.requestParams.has('cursorMark')
      ? 0
      : Number.parseInt(ctx.requestParams.get('start') || '0', 10));
    responseMap.set('numFoundExact', true);
    responseMap.set('docs', hitsArray.map(hitToDoc));
    ctx.responseBody.set('response', responseMap);

    ctx.responseBody.delete('hits');
    ctx.responseBody.delete('took');
    ctx.responseBody.delete('timed_out');
    ctx.responseBody.delete('_shards');
  },
};
