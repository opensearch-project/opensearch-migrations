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

  // ---------------------------------------------------------------------------
  // Nested aggregation response conversion
  // ---------------------------------------------------------------------------

  describe('nested aggregation conversion in buckets', () => {
    it('should convert nested terms agg results within buckets', () => {
      // Simulate: categories → brands (nested terms inside each bucket)
      const nestedBrandsAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 'acme'], ['doc_count', 10]]),
          new Map<string, any>([['key', 'globex'], ['doc_count', 5]]),
        ]],
      ]);
      const outerBuckets = [
        new Map<string, any>([
          ['key', 'electronics'],
          ['doc_count', 15],
          ['brands', nestedBrandsAgg],
        ]),
      ];
      const aggs = new Map([
        ['categories', new Map<string, any>([['buckets', outerBuckets]])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const categories: JavaMap = facets.get('categories');
      const catBuckets: JavaMap[] = categories.get('buckets');

      expect(catBuckets).toHaveLength(1);
      expect(catBuckets[0].get('val')).toBe('electronics');
      expect(catBuckets[0].get('count')).toBe(15);

      // Nested facet result
      const brands: JavaMap = catBuckets[0].get('brands');
      expect(brands).toBeDefined();
      const brandBuckets: JavaMap[] = brands.get('buckets');
      expect(brandBuckets).toHaveLength(2);
      expect(brandBuckets[0].get('val')).toBe('acme');
      expect(brandBuckets[0].get('count')).toBe(10);
      expect(brandBuckets[1].get('val')).toBe('globex');
      expect(brandBuckets[1].get('count')).toBe(5);
    });

    it('should not add nested keys for buckets with no nested aggs', () => {
      const aggs = new Map([
        ['categories', osTermsAgg([{ key: 'food', doc_count: 3 }])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 10 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const bucket: JavaMap = facets.get('categories').get('buckets')[0];
      // Should only have val and count — no extra keys
      expect(bucket.size).toBe(2);
      expect(bucket.has('val')).toBe(true);
      expect(bucket.has('count')).toBe(true);
    });

    it('should handle multiple nested aggs within the same bucket', () => {
      const nestedBrandsAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 'acme'], ['doc_count', 8]]),
        ]],
      ]);
      const nestedPricesAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 0], ['doc_count', 3]]),
          new Map<string, any>([['key', 25], ['doc_count', 5]]),
        ]],
      ]);
      const outerBuckets = [
        new Map<string, any>([
          ['key', 'electronics'],
          ['doc_count', 8],
          ['brands', nestedBrandsAgg],
          ['prices', nestedPricesAgg],
        ]),
      ];
      const aggs = new Map([
        ['categories', new Map<string, any>([['buckets', outerBuckets]])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 50 });
      response.apply(ctx);

      const bucket: JavaMap = ctx.responseBody.get('facets').get('categories').get('buckets')[0];
      expect(bucket.has('brands')).toBe(true);
      expect(bucket.has('prices')).toBe(true);
      expect(bucket.get('brands').get('buckets')).toHaveLength(1);
      expect(bucket.get('prices').get('buckets')).toHaveLength(2);
    });
  });

  describe('nested aggregation conversion in filter aggs', () => {
    it('should convert nested terms agg results within a filter aggregation', () => {
      // Simulate: expensive (filter) → categories (nested terms)
      const nestedCategoriesAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 'electronics'], ['doc_count', 5]]),
          new Map<string, any>([['key', 'clothing'], ['doc_count', 2]]),
        ]],
      ]);
      const filterAgg = new Map<string, any>([
        ['doc_count', 7],
        ['categories', nestedCategoriesAgg],
      ]);
      const aggs = new Map([['expensive', filterAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const expensive: JavaMap = facets.get('expensive');
      expect(expensive.get('count')).toBe(7);

      // Nested facet result
      const categories: JavaMap = expensive.get('categories');
      expect(categories).toBeDefined();
      const catBuckets: JavaMap[] = categories.get('buckets');
      expect(catBuckets).toHaveLength(2);
      expect(catBuckets[0].get('val')).toBe('electronics');
      expect(catBuckets[0].get('count')).toBe(5);
      expect(catBuckets[1].get('val')).toBe('clothing');
      expect(catBuckets[1].get('count')).toBe(2);
    });

    it('should not add nested keys for filter aggs with no nested aggs', () => {
      const filterAgg = new Map<string, any>([['doc_count', 17]]);
      const aggs = new Map([['expensive', filterAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const expensive: JavaMap = ctx.responseBody.get('facets').get('expensive');
      // Should only have count — no extra keys
      expect(expensive.size).toBe(1);
      expect(expensive.has('count')).toBe(true);
    });
  });

  describe('date_histogram bucket conversion with key_as_string', () => {
    it('should use key_as_string as val and filter it from nested agg detection', () => {
      // date_histogram buckets have key (epoch millis), key_as_string (ISO), and doc_count
      // All three are in BUCKET_META_KEYS and should NOT be treated as nested aggs
      const nestedBrandsAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 'acme'], ['doc_count', 4]]),
        ]],
      ]);
      const dateHistBuckets = [
        new Map<string, any>([
          ['key', 1704067200000],           // epoch millis
          ['key_as_string', '2024-01-01'],   // formatted date
          ['doc_count', 10],
          ['brands', nestedBrandsAgg],
        ]),
        new Map<string, any>([
          ['key', 1706745600000],
          ['key_as_string', '2024-02-01'],
          ['doc_count', 7],
        ]),
      ];
      const aggs = new Map([
        ['monthly', new Map<string, any>([['buckets', dateHistBuckets]])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 17 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      const monthlyBuckets: JavaMap[] = facets.get('monthly').get('buckets');

      expect(monthlyBuckets).toHaveLength(2);

      // First bucket: key_as_string should be used as val, nested brands should be converted
      expect(monthlyBuckets[0].get('val')).toBe('2024-01-01');
      expect(monthlyBuckets[0].get('count')).toBe(10);
      expect(monthlyBuckets[0].has('brands')).toBe(true);
      expect(monthlyBuckets[0].get('brands').get('buckets')).toHaveLength(1);
      expect(monthlyBuckets[0].get('brands').get('buckets')[0].get('val')).toBe('acme');

      // Second bucket: no nested aggs, only val + count
      expect(monthlyBuckets[1].get('val')).toBe('2024-02-01');
      expect(monthlyBuckets[1].get('count')).toBe(7);
      expect(monthlyBuckets[1].size).toBe(2);
    });
  });

  describe('multi-level nested response conversion', () => {
    it('should handle three levels of nested aggregation results', () => {
      // Simulate: categories → brands → price_ranges (3 levels)
      const priceRangeAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 0], ['doc_count', 2]]),
          new Map<string, any>([['key', 50], ['doc_count', 3]]),
        ]],
      ]);
      const brandsAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([
            ['key', 'acme'],
            ['doc_count', 5],
            ['price_ranges', priceRangeAgg],
          ]),
        ]],
      ]);
      const categoriesAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([
            ['key', 'electronics'],
            ['doc_count', 5],
            ['brands', brandsAgg],
          ]),
        ]],
      ]);
      const aggs = new Map([['categories', categoriesAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');

      // Level 1: categories
      const catBucket: JavaMap = facets.get('categories').get('buckets')[0];
      expect(catBucket.get('val')).toBe('electronics');
      expect(catBucket.get('count')).toBe(5);

      // Level 2: brands (nested in category bucket)
      const brandBucket: JavaMap = catBucket.get('brands').get('buckets')[0];
      expect(brandBucket.get('val')).toBe('acme');
      expect(brandBucket.get('count')).toBe(5);

      // Level 3: price_ranges (nested in brand bucket)
      const priceRangeBuckets: JavaMap[] = brandBucket.get('price_ranges').get('buckets');
      expect(priceRangeBuckets).toHaveLength(2);
      expect(priceRangeBuckets[0].get('val')).toBe(0);
      expect(priceRangeBuckets[0].get('count')).toBe(2);
      expect(priceRangeBuckets[1].get('val')).toBe(50);
      expect(priceRangeBuckets[1].get('count')).toBe(3);
    });

    it('should handle mixed nesting: query (filter) → terms → range', () => {
      // Simulates: expensive (filter agg) → categories (terms) → price_ranges (histogram)
      const priceRangeAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([['key', 0], ['doc_count', 1]]),
          new Map<string, any>([['key', 50], ['doc_count', 4]]),
        ]],
      ]);
      const categoriesAgg = new Map<string, any>([
        ['buckets', [
          new Map<string, any>([
            ['key', 'electronics'],
            ['doc_count', 5],
            ['price_ranges', priceRangeAgg],
          ]),
          new Map<string, any>([
            ['key', 'clothing'],
            ['doc_count', 3],
          ]),
        ]],
      ]);
      // Filter agg (from query facet): has doc_count + nested terms agg
      const filterAgg = new Map<string, any>([
        ['doc_count', 8],
        ['categories', categoriesAgg],
      ]);
      const aggs = new Map([['expensive', filterAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');

      // Level 1: filter agg → count
      const expensive: JavaMap = facets.get('expensive');
      expect(expensive.get('count')).toBe(8);

      // Level 2: nested terms agg within the filter
      const categories: JavaMap = expensive.get('categories');
      expect(categories).toBeDefined();
      const catBuckets: JavaMap[] = categories.get('buckets');
      expect(catBuckets).toHaveLength(2);
      expect(catBuckets[0].get('val')).toBe('electronics');
      expect(catBuckets[0].get('count')).toBe(5);
      expect(catBuckets[1].get('val')).toBe('clothing');
      expect(catBuckets[1].get('count')).toBe(3);

      // Level 3: nested range agg within the terms bucket
      const priceRangeBkts: JavaMap[] = catBuckets[0].get('price_ranges').get('buckets');
      expect(priceRangeBkts).toHaveLength(2);
      expect(priceRangeBkts[0].get('val')).toBe(0);
      expect(priceRangeBkts[0].get('count')).toBe(1);
      expect(priceRangeBkts[1].get('val')).toBe(50);
      expect(priceRangeBkts[1].get('count')).toBe(4);

      // Clothing bucket should have no nested aggs
      expect(catBuckets[1].size).toBe(2);
    });
  });

  describe('metric aggregation (stat facet) response conversion', () => {
    it('should return scalar value for avg metric agg', () => {
      const avgAgg = new Map<string, any>([['value', 29.95]]);
      const aggs = new Map([['avg_price', avgAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('avg_price')).toBe(29.95);
    });

    it('should return scalar value for value_count metric agg', () => {
      const countAgg = new Map<string, any>([['value', 42]]);
      const aggs = new Map([['num_products', countAgg]]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('num_products')).toBe(42);
    });

    it('should handle metric aggs nested inside bucket aggs', () => {
      const avgAgg = new Map<string, any>([['value', 15.5]]);
      const outerBuckets = [
        new Map<string, any>([
          ['key', 'electronics'],
          ['doc_count', 10],
          ['avg_price', avgAgg],
        ]),
      ];
      const aggs = new Map([
        ['categories', new Map<string, any>([['buckets', outerBuckets]])],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 50 });
      response.apply(ctx);

      const bucket: JavaMap = ctx.responseBody.get('facets').get('categories').get('buckets')[0];
      expect(bucket.get('avg_price')).toBe(15.5);
    });

    it('should handle metric aggs alongside bucket aggs at top level', () => {
      const avgAgg = new Map<string, any>([['value', 25]]);
      const aggs = new Map<string, any>([
        ['categories', osTermsAgg([{ key: 'food', doc_count: 3 }])],
        ['avg_price', avgAgg],
      ]);
      const body = new Map<string, any>([['aggregations', aggs]]);
      const ctx = buildCtx(body, { hitsTotal: 100 });
      response.apply(ctx);

      const facets: JavaMap = ctx.responseBody.get('facets');
      expect(facets.get('avg_price')).toBe(25);
      expect(facets.get('categories').get('buckets')).toHaveLength(1);
    });
  });
});
