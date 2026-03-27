import { describe, it, expect } from 'vitest';
import { response, convertAggregations } from './aggs-to-facets';
import type { ResponseContext, JavaMap } from '../context';

/**
 * Helper: build a ResponseContext with the given responseBody and optional hits for total count.
 */
function buildCtx(responseBody: Map<string, any>, opts?: { hitsTotal?: number }): ResponseContext {
  // If hitsTotal is provided and responseBody doesn't have hits, add them
  if (opts?.hitsTotal != null && !responseBody.has('hits')) {
    const total = new Map<string, any>([['value', opts.hitsTotal]]);
    responseBody.set(
      'hits',
      new Map<string, any>([
        ['total', total],
        ['hits', []],
      ]),
    );
  }
  return {
    request: new Map() as unknown as JavaMap,
    response: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    requestParams: new URLSearchParams(),
    responseBody: responseBody as unknown as JavaMap,
  };
}

/** Helper: build an OpenSearch terms aggregation result with buckets. */
function osTermsAgg(buckets: { key: string; doc_count: number }[]): JavaMap {
  const mappedBuckets = buckets.map(
    (b) =>
      new Map<string, any>([
        ['key', b.key],
        ['doc_count', b.doc_count],
      ]),
  );
  return new Map<string, any>([['buckets', mappedBuckets]]) as unknown as JavaMap;
}

describe('aggs-to-facets MicroTransform', () => {
  describe('match', () => {
    it('should match when aggregations is present in responseBody', () => {
      const body = new Map<string, any>([['aggregations', new Map()]]);
      const ctx = buildCtx(body);
      expect(response.match!(ctx)).toBe(true);
    });

    it('should not match when aggregations is absent', () => {
      const body = new Map<string, any>();
      const ctx = buildCtx(body);
      expect(response.match!(ctx)).toBe(false);
    });
  });

  describe('apply', () => {
    it('should remove aggregations from responseBody', () => {
      const aggs = new Map([['myFacet', osTermsAgg([{ key: 'a', doc_count: 10 }])]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);
      expect(ctx.responseBody.has('aggregations')).toBe(false);
    });

    it('should set facets on responseBody', () => {
      const aggs = new Map([['myFacet', osTermsAgg([{ key: 'a', doc_count: 10 }])]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);
      expect(ctx.responseBody.has('facets')).toBe(true);
    });

    it('should set facets.count from hits.total.value', () => {
      const aggs = new Map([['myFacet', osTermsAgg([])]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 42 });
      response.apply(ctx);
      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('count')).toBe(42);
    });

    it('should set facets.count from response.numFound when hits is absent', () => {
      const aggs = new Map([['myFacet', osTermsAgg([])]]);
      const solrResponse = new Map([['numFound', 55]]);
      const body = new Map<string, any>([
        ['aggregations', aggs],
        ['response', solrResponse],
      ]);
      const ctx = buildCtx(body);
      response.apply(ctx);
      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('count')).toBe(55);
    });

    it('should set facets.count to 0 when no count source is available', () => {
      const aggs = new Map([['myFacet', osTermsAgg([])]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body);
      response.apply(ctx);
      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('count')).toBe(0);
    });
  });

  describe('terms aggregation bucket conversion', () => {
    it('should convert key to val and doc_count to count', () => {
      const aggs = new Map([['categories', osTermsAgg([{ key: 'electronics', doc_count: 25 }])]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const categories: JavaMap = facets.get('categories');
      const buckets: JavaMap[] = categories.get('buckets');

      expect(buckets).toHaveLength(1);
      expect(buckets[0].get('val')).toBe('electronics');
      expect(buckets[0].get('count')).toBe(25);
    });

    it('should handle multiple buckets', () => {
      const aggs = new Map([
        [
          'status',
          osTermsAgg([
            { key: 'active', doc_count: 50 },
            { key: 'inactive', doc_count: 30 },
            { key: 'pending', doc_count: 20 },
          ]),
        ],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const status: JavaMap = facets.get('status');
      const buckets: JavaMap[] = status.get('buckets');

      expect(buckets).toHaveLength(3);
      expect(buckets[0].get('val')).toBe('active');
      expect(buckets[0].get('count')).toBe(50);
      expect(buckets[1].get('val')).toBe('inactive');
      expect(buckets[1].get('count')).toBe(30);
      expect(buckets[2].get('val')).toBe('pending');
      expect(buckets[2].get('count')).toBe(20);
    });

    it('should handle empty buckets array', () => {
      const aggs = new Map([['myFacet', osTermsAgg([])]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 0 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const myFacet: JavaMap = facets.get('myFacet');
      const buckets: JavaMap[] = myFacet.get('buckets');
      expect(buckets).toHaveLength(0);
    });

    it('should handle multiple aggregations', () => {
      const aggs = new Map([
        ['categories', osTermsAgg([{ key: 'electronics', doc_count: 25 }])],
        ['brands', osTermsAgg([{ key: 'acme', doc_count: 15 }])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.has('categories')).toBe(true);
      expect(facets.has('brands')).toBe(true);

      const catBuckets: JavaMap[] = facets.get('categories').get('buckets');
      expect(catBuckets[0].get('val')).toBe('electronics');

      const brandBuckets: JavaMap[] = facets.get('brands').get('buckets');
      expect(brandBuckets[0].get('val')).toBe('acme');
    });
  });

  describe('filter aggregation (query facet) conversion', () => {
    it('should convert doc_count to count for a filter aggregation (no buckets)', () => {
      const filterAgg = new Map<string, any>([['doc_count', 17]]);
      const aggs = new Map([['expensive', filterAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const expensive: JavaMap = facets.get('expensive');
      expect(expensive.get('count')).toBe(17);
      expect(expensive.has('buckets')).toBe(false);
    });

    it('should handle filter aggregations alongside terms aggregations', () => {
      const filterAgg = new Map<string, any>([['doc_count', 5]]);
      const aggs = new Map<string, any>([
        ['cheap', filterAgg],
        ['categories', osTermsAgg([{ key: 'food', doc_count: 3 }])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 50 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('cheap').get('count')).toBe(5);
      expect(facets.get('categories').get('buckets')).toHaveLength(1);
    });
  });

  describe('error handling', () => {
    it('should throw when an aggregation result is not a Map', () => {
      const aggs = new Map([['myFacet', 'not-a-map']]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 10 });
      expect(() => response.apply(ctx)).toThrow('Expected aggregation result to be a Map');
    });

    it('should throw when a bucket inside an aggregation is not a Map', () => {
      const badAgg = new Map<string, any>([['buckets', ['not-a-map']]]);
      const aggs = new Map([['myFacet', badAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 10 });
      expect(() => response.apply(ctx)).toThrow('Expected aggregation bucket to be a Map');
    });
  });

  describe('convertAggregations (exported helper)', () => {
    it('should produce a facets Map with count and converted aggs', () => {
      const aggs = new Map([
        ['myFacet', osTermsAgg([{ key: 'x', doc_count: 5 }])],
      ]) as unknown as JavaMap;

      const facets = convertAggregations(aggs, 42);
      expect(facets.get('count')).toBe(42);
      expect(facets.has('myFacet')).toBe(true);

      const buckets: JavaMap[] = facets.get('myFacet').get('buckets');
      expect(buckets[0].get('val')).toBe('x');
      expect(buckets[0].get('count')).toBe(5);
    });
  });
});
