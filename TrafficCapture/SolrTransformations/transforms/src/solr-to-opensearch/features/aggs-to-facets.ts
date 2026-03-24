/**
 * Aggs to facets — convert OpenSearch aggregations response to Solr facets response.
 *
 * Response-only. Reverses the json-facets request transform:
 *   OpenSearch aggregations → Solr JSON Facet API response format.
 *
 * OpenSearch terms agg response:
 *   { "aggregations": { "myFacet": { "buckets": [{ "key": "x", "doc_count": 5 }] } } }
 *
 * Solr JSON Facet API response:
 *   { "facets": { "count": N, "myFacet": { "buckets": [{ "val": "x", "count": 5 }] } } }
 *
 * Uses .get()/.set() on Java Maps throughout for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { ResponseContext, JavaMap } from '../context';
import { isMapLike } from './utils';

/**
 * Convert a single OpenSearch aggregation bucket to a Solr facet bucket.
 *
 * Maps: key → val, doc_count → count.
 *
 * For date_histogram buckets, OpenSearch returns `key` as epoch millis and
 * `key_as_string` as the formatted date string. Solr returns ISO-8601 strings,
 * so we prefer `key_as_string` when present.
 */
function convertBucket(osBucket: JavaMap): JavaMap {
  const solrBucket = new Map<string, any>();
  const keyAsString = osBucket.get('key_as_string');
  solrBucket.set('val', keyAsString ?? osBucket.get('key'));
  solrBucket.set('count', osBucket.get('doc_count'));
  return solrBucket;
}

/**
 * Convert a single OpenSearch aggregation result to Solr facet result.
 *
 * Currently supports terms aggregations (which have a `buckets` array).
 */
function convertSingleAgg(aggResult: any): JavaMap {
  const facetResult = new Map<string, any>();

  if (!isMapLike(aggResult)) {
    throw new Error(`Expected aggregation result to be a Map, got: ${typeof aggResult}`);
  }

  const buckets: any[] = aggResult.get('buckets');
  if (Array.isArray(buckets)) {
    const solrBuckets: JavaMap[] = [];
    for (const bucket of buckets) {
      if (!isMapLike(bucket)) {
        throw new Error(`Expected aggregation bucket to be a Map, got: ${typeof bucket}`);
      }
      solrBuckets.push(convertBucket(bucket));
    }
    facetResult.set('buckets', solrBuckets);
  }

  return facetResult;
}

/**
 * Convert all OpenSearch aggregations to Solr facets format.
 *
 * @param aggregations - The OpenSearch aggregations Map from the response
 * @param totalCount - The total document count for the top-level facets.count
 * @returns A Solr-compatible facets Map
 */
export function convertAggregations(aggregations: JavaMap, totalCount: number): JavaMap {
  const facets = new Map<string, any>();
  facets.set('count', totalCount);

  for (const name of aggregations.keys()) {
    facets.set(name, convertSingleAgg(aggregations.get(name)));
  }

  return facets;
}

export const response: MicroTransform<ResponseContext> = {
  name: 'aggs-to-facets',
  match: (ctx) => ctx.responseBody.has('aggregations'),
  apply: (ctx) => {
    const aggregations: JavaMap = ctx.responseBody.get('aggregations');

    // Derive total count from hits.total.value (if still present) or response.numFound
    let totalCount = 0;
    const hits = ctx.responseBody.get('hits');
    if (isMapLike(hits)) {
      const total = hits.get('total');
      if (isMapLike(total)) {
        totalCount = total.get('value') ?? 0;
      } else if (typeof total === 'number') {
        totalCount = total;
      }
    }
    // Fallback: if hits-to-docs already ran, check response.numFound
    if (totalCount === 0) {
      const solrResponse = ctx.responseBody.get('response');
      if (isMapLike(solrResponse)) {
        totalCount = solrResponse.get('numFound') ?? 0;
      }
    }

    ctx.responseBody.set('facets', convertAggregations(aggregations, totalCount));
    ctx.responseBody.delete('aggregations');
  },
};
