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

/** Keys that are part of the bucket structure itself, not nested aggregation results. */
const BUCKET_META_KEYS = new Set(['key', 'key_as_string', 'doc_count']);

/**
 * Convert a single OpenSearch aggregation bucket to a Solr facet bucket.
 *
 * Maps: key → val, doc_count → count.
 *
 * For date_histogram buckets, OpenSearch returns `key` as epoch millis and
 * `key_as_string` as the formatted date string. Solr returns ISO-8601 strings,
 * so we prefer `key_as_string` when present.
 *
 * Any remaining keys in the bucket (not in BUCKET_META_KEYS) are treated as
 * nested aggregation results and recursively converted.
 */
function convertBucket(osBucket: JavaMap): JavaMap {
  const solrBucket = new Map<string, any>();
  const keyAsString = osBucket.get('key_as_string');
  solrBucket.set('val', keyAsString ?? osBucket.get('key'));
  solrBucket.set('count', osBucket.get('doc_count'));

  // Convert nested aggregation results within this bucket
  for (const nestedKey of osBucket.keys()) {
    if (BUCKET_META_KEYS.has(nestedKey)) continue;
    const nestedAgg = osBucket.get(nestedKey);
    if (isMapLike(nestedAgg)) {
      solrBucket.set(nestedKey, convertSingleAgg(nestedAgg));
    }
  }

  return solrBucket;
}

/** Keys that are part of the filter aggregation structure itself, not nested aggregation results. */
const FILTER_META_KEYS = new Set(['doc_count']);

/**
 * Copy nested aggregation results from a source Map into a target Map.
 *
 * Iterates over all keys in `source`, skipping those in `metaKeys`, and
 * recursively converts any Map-like values via `convertSingleAgg`.
 */
function copyNestedAggs(target: JavaMap, source: JavaMap, metaKeys: Set<string>): void {
  for (const key of source.keys()) {
    if (metaKeys.has(key)) continue;
    const nested = source.get(key);
    if (isMapLike(nested)) {
      target.set(key, convertSingleAgg(nested));
    }
  }
}

/** Convert a bucket-based aggregation (terms / range / histogram) to Solr format. */
function convertBucketAgg(buckets: any[]): JavaMap {
  const facetResult = new Map<string, any>();
  const solrBuckets: JavaMap[] = [];
  for (const bucket of buckets) {
    if (!isMapLike(bucket)) {
      throw new Error(`Expected aggregation bucket to be a Map, got: ${typeof bucket}`);
    }
    solrBuckets.push(convertBucket(bucket));
  }
  facetResult.set('buckets', solrBuckets);
  return facetResult;
}

/** Convert a filter aggregation (from query facet) to Solr format. */
function convertFilterAgg(aggResult: JavaMap): JavaMap {
  const facetResult = new Map<string, any>();
  facetResult.set('count', aggResult.get('doc_count'));
  copyNestedAggs(facetResult, aggResult, FILTER_META_KEYS);
  return facetResult;
}

/**
 * Convert a single OpenSearch aggregation result to Solr facet result.
 *
 * Supports:
 *   - Terms / range / histogram aggregations (which have a `buckets` array)
 *   - Filter aggregations (from query facets, which have `doc_count`)
 *   - Metric aggregations (avg, sum, min, max, value_count, cardinality — which have a `value` key)
 *
 * For both bucket-based and filter aggregations, any nested aggregation results
 * are recursively converted.
 *
 * Metric aggregations return their scalar value directly, matching Solr's
 * json.facet response format where stat facets are plain numbers:
 *   Solr: { "facets": { "count": 100, "avg_price": 29.95 } }
 */
function convertSingleAgg(aggResult: any): JavaMap | number {
  if (!isMapLike(aggResult)) {
    throw new Error(`Expected aggregation result to be a Map, got: ${typeof aggResult}`);
  }

  const buckets: any[] = aggResult.get('buckets');
  if (Array.isArray(buckets)) {
    return convertBucketAgg(buckets);
  }
  // Metric aggregations (avg, sum, min, max, value_count, cardinality)
  // return { "value": <number> }. Solr returns the scalar directly.
  if (aggResult.has('value') && !aggResult.has('doc_count')) {
    return aggResult.get('value');
  }
  if (aggResult.has('doc_count')) {
    return convertFilterAgg(aggResult);
  }
  return new Map<string, any>();
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
