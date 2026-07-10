import { describe, it, expect, vi } from 'vitest';
import { request } from './json-facets';
import type { RequestContext, JavaMap } from '../context';
import { convertSort } from './utils';

const FACET_NAME = 'myFacet';

// region Test helpers

/**
 * Helper: build a RequestContext whose body contains a json.facet Map
 * with one named facet definition.
 */
function ctxWithBodyFacet(obj: Record<string, any>): RequestContext {
  const def = new Map(Object.entries(obj)) as unknown as JavaMap;
  const jsonFacet = new Map([[FACET_NAME, def]]) as unknown as JavaMap;
  const body = new Map([['json.facet', jsonFacet]]) as unknown as JavaMap;
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams(),
    body,
    emitMetric: () => {},
    _metrics: new Map(),
  };
}

/**
 * Helper: build a RequestContext whose params contain a json.facet query-string
 * parameter (JSON-encoded string) with one named facet definition.
 */
function ctxWithParamFacet(obj: Record<string, any>): RequestContext {
  const facetJson = JSON.stringify({ [FACET_NAME]: obj });
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams({ 'json.facet': facetJson }),
    body: new Map() as unknown as JavaMap,
    emitMetric: () => {},
    _metrics: new Map(),
  };
}

/** Apply the micro-transform and return the aggs result from the body. */
function applyAndGetAggs(ctx: RequestContext): JavaMap {
  request.apply(ctx);
  return ctx.body.get('aggs');
}

/** Extract the inner "terms" map from the aggs result for the default facet name. */
function termsInner(aggs: JavaMap): JavaMap {
  return aggs.get(FACET_NAME).get('terms');
}

/** Extract the inner "histogram" map from the aggs result for the default facet name. */
function histogramInner(aggs: JavaMap): JavaMap {
  return aggs.get(FACET_NAME).get('histogram');
}

/** Extract the inner "date_histogram" map from the aggs result for the default facet name. */
function dateHistogramInner(aggs: JavaMap): JavaMap {
  return aggs.get(FACET_NAME).get('date_histogram');
}

/** Extract the inner "range" map from the aggs result for the default facet name. */
function rangeInner(aggs: JavaMap): JavaMap {
  return aggs.get(FACET_NAME).get('range');
}

/** Extract the inner "filter" map from the aggs result for the default facet name. */
function filterInner(aggs: JavaMap): JavaMap {
  return aggs.get(FACET_NAME).get('filter');
}

/** Build a terms facet context, apply the transform, and return the inner terms map. */
function applyBodyTerms(obj: Record<string, any>): JavaMap {
  return termsInner(applyAndGetAggs(ctxWithBodyFacet({ type: 'terms', ...obj })));
}

/** Build a range facet context, apply the transform, and return the inner histogram map. */
function applyBodyHistogram(obj: Record<string, any>): JavaMap {
  return histogramInner(applyAndGetAggs(ctxWithBodyFacet({ type: 'range', ...obj })));
}

/** Build a range facet context (with ranges), apply the transform, and return the inner range map. */
function applyBodyRange(obj: Record<string, any>): JavaMap {
  return rangeInner(applyAndGetAggs(ctxWithBodyFacet({ type: 'range', ...obj })));
}

/** Build a date range facet context, apply the transform, and return the inner date_histogram map. */
function applyBodyDateHistogram(obj: Record<string, any>): JavaMap {
  return dateHistogramInner(applyAndGetAggs(ctxWithBodyFacet({ type: 'range', ...obj })));
}

/** Build a query facet context, apply the transform, and return the inner filter map. */
function applyBodyQuery(obj: Record<string, any>): JavaMap {
  return filterInner(applyAndGetAggs(ctxWithBodyFacet({ type: 'query', ...obj })));
}

// endregion

// General transform behaviour (match / apply / source selection)

describe('json-facets MicroTransform', () => {
  describe('match', () => {
    it('should match when json.facet is in the body', () => {
      const ctx = ctxWithBodyFacet({ type: 'terms', field: 'category' });
      expect(request.match!(ctx)).toBe(true);
    });

    it('should match when json.facet is in the query params', () => {
      const ctx = ctxWithParamFacet({ type: 'terms', field: 'category' });
      expect(request.match!(ctx)).toBe(true);
    });

    it('should not match when json.facet is absent', () => {
      const ctx: RequestContext = {
        msg: new Map() as unknown as JavaMap,
        endpoint: 'select',
        collection: 'testcollection',
        params: new URLSearchParams(),
        body: new Map() as unknown as JavaMap,
        emitMetric: () => {},
        _metrics: new Map(),
      };
      expect(request.match!(ctx)).toBe(false);
    });
  });

  describe('apply — body source', () => {
    it('should remove json.facet from body after processing', () => {
      const ctx = ctxWithBodyFacet({ type: 'terms', field: 'category' });
      request.apply(ctx);
      expect(ctx.body.has('json.facet')).toBe(false);
    });

    it('should set aggs on the body', () => {
      const ctx = ctxWithBodyFacet({ type: 'terms', field: 'category' });
      request.apply(ctx);
      expect(ctx.body.has('aggs')).toBe(true);
    });
  });

  describe('apply — query-string source', () => {
    it('should set aggs from a json.facet query param', () => {
      const ctx = ctxWithParamFacet({ type: 'terms', field: 'category' });
      request.apply(ctx);
      expect(ctx.body.has('aggs')).toBe(true);
      const inner = termsInner(ctx.body.get('aggs'));
      expect(inner.get('field')).toBe('category');
    });
  });
});

// Terms facet → OpenSearch terms aggregation

describe('terms facet conversion', () => {
  it('should set the field on the terms aggregation', () => {
    const inner = applyBodyTerms({ field: 'category' });
    expect(inner.get('field')).toBe('category');
  });

  it('should map limit to size', () => {
    const inner = applyBodyTerms({ field: 'category', limit: 5 });
    expect(inner.get('size')).toBe(5);
  });

  it('should not set size when limit is absent', () => {
    const inner = applyBodyTerms({ field: 'category' });
    expect(inner.has('size')).toBe(false);
  });

  it('should combine offset with default limit into size', () => {
    const inner = applyBodyTerms({ field: 'category', offset: 10 });
    // offset=10 + default limit=10 → size=20
    expect(inner.get('size')).toBe(20);
  });

  it('should map mincount to min_doc_count', () => {
    const inner = applyBodyTerms({ field: 'category', mincount: 2 });
    expect(inner.get('min_doc_count')).toBe(2);
  });

  it('should not set min_doc_count when mincount is absent', () => {
    const inner = applyBodyTerms({ field: 'category' });
    expect(inner.has('min_doc_count')).toBe(false);
  });

  it('should map prefix to an include regex pattern', () => {
    const inner = applyBodyTerms({ field: 'category', prefix: 'foo' });
    expect(inner.get('include')).toBe('foo.*');
  });

  it('should not set include when prefix is absent', () => {
    const inner = applyBodyTerms({ field: 'category' });
    expect(inner.has('include')).toBe(false);
  });

  it('should set missing to empty string when missing is true', () => {
    const inner = applyBodyTerms({ field: 'category', missing: true });
    expect(inner.get('missing')).toBe('');
  });

  it('should not set missing when missing is false', () => {
    const inner = applyBodyTerms({ field: 'category', missing: false });
    expect(inner.has('missing')).toBe(false);
  });

  it('should not set missing when missing is absent', () => {
    const inner = applyBodyTerms({ field: 'category' });
    expect(inner.has('missing')).toBe(false);
  });

  describe('sort conversion', () => {
    it('should map "count desc" to order {_count: "desc"}', () => {
      const inner = applyBodyTerms({ field: 'category', sort: 'count desc' });
      expect(inner.get('order').get('_count')).toBe('desc');
    });

    it('should map "count asc" to order {_count: "asc"}', () => {
      const inner = applyBodyTerms({ field: 'category', sort: 'count asc' });
      expect(inner.get('order').get('_count')).toBe('asc');
    });

    it('should map "index asc" to order {_key: "asc"}', () => {
      const inner = applyBodyTerms({ field: 'category', sort: 'index asc' });
      expect(inner.get('order').get('_key')).toBe('asc');
    });

    it('should map "index desc" to order {_key: "desc"}', () => {
      const inner = applyBodyTerms({ field: 'category', sort: 'index desc' });
      expect(inner.get('order').get('_key')).toBe('desc');
    });

    it('should default sort direction to desc when direction is omitted', () => {
      const inner = applyBodyTerms({ field: 'category', sort: 'count' });
      expect(inner.get('order').get('_count')).toBe('desc');
    });

    it('should pass through unknown sort keys as-is', () => {
      const inner = applyBodyTerms({ field: 'category', sort: 'my_metric asc' });
      expect(inner.get('order').get('my_metric')).toBe('asc');
    });

    it('should not set order when sort is absent', () => {
      const inner = applyBodyTerms({ field: 'category' });
      expect(inner.has('order')).toBe(false);
    });
  });

  it('should handle limit of 0', () => {
    const inner = applyBodyTerms({ field: 'category', limit: 0 });
    expect(inner.get('size')).toBe(0);
  });

  it('should handle mincount of 0', () => {
    const inner = applyBodyTerms({ field: 'category', mincount: 0 });
    expect(inner.get('min_doc_count')).toBe(0);
  });

  it('should handle all options combined', () => {
    const inner = applyBodyTerms({
      field: 'status',
      limit: 20,
      offset: 5,
      mincount: 1,
      prefix: 'active',
      missing: true,
      sort: 'index asc',
    });

    expect(inner.get('field')).toBe('status');
    // offset=5 + limit=20 → size=25
    expect(inner.get('size')).toBe(25);
    expect(inner.get('min_doc_count')).toBe(1);
    expect(inner.get('include')).toBe('active.*');
    expect(inner.get('missing')).toBe('');
    expect(inner.get('order').get('_key')).toBe('asc');
  });

  it('should only have field when no optional properties are provided', () => {
    const inner = applyBodyTerms({ field: 'category' });
    expect(inner.size).toBe(1);
    expect(inner.get('field')).toBe('category');
  });

  it('should return a Map with exactly one top-level "terms" key', () => {
    const aggs = applyAndGetAggs(
      ctxWithBodyFacet({ type: 'terms', field: 'category', limit: 10 }),
    );
    const agg = aggs.get(FACET_NAME);
    expect(agg.size).toBe(1);
    expect(agg.has('terms')).toBe(true);
  });
});

// Uniform range facet (start/end/gap) → OpenSearch histogram aggregation

describe('uniform range facet conversion (histogram)', () => {
  it('should produce a histogram aggregation with field and interval', () => {
    const inner = applyBodyHistogram({ field: 'price', start: 0, end: 100, gap: 10 });
    expect(inner.get('field')).toBe('price');
    expect(inner.get('interval')).toBe(10);
  });

  it('should return a Map with exactly one top-level "histogram" key', () => {
    const aggs = applyAndGetAggs(
      ctxWithBodyFacet({ type: 'range', field: 'price', start: 0, end: 100, gap: 10 }),
    );
    const agg = aggs.get(FACET_NAME);
    expect(agg.size).toBe(1);
    expect(agg.has('histogram')).toBe(true);
  });

  it('should set extended_bounds from start and end', () => {
    const inner = applyBodyHistogram({ field: 'price', start: 0, end: 100, gap: 10 });
    const bounds = inner.get('extended_bounds');
    expect(bounds).toBeDefined();
    expect(bounds.get('min')).toBe(0);
    // max = end - gap = 100 - 10 = 90
    expect(bounds.get('max')).toBe(90);
  });

  it('should set extended_bounds.max to end when gap is absent', () => {
    const inner = applyBodyHistogram({ field: 'price', start: 0, end: 100 });
    const bounds = inner.get('extended_bounds');
    expect(bounds.get('max')).toBe(100);
  });

  it('should set only extended_bounds.min when end is absent', () => {
    const inner = applyBodyHistogram({ field: 'price', start: 0, gap: 10 });
    const bounds = inner.get('extended_bounds');
    expect(bounds.get('min')).toBe(0);
    expect(bounds.has('max')).toBe(false);
  });

  it('should not set extended_bounds when start and end are both absent', () => {
    const inner = applyBodyHistogram({ field: 'price', gap: 10 });
    expect(inner.has('extended_bounds')).toBe(false);
  });

  it('should map mincount to min_doc_count', () => {
    const inner = applyBodyHistogram({
      field: 'price',
      start: 0,
      end: 100,
      gap: 10,
      mincount: 1,
    });
    expect(inner.get('min_doc_count')).toBe(1);
  });

  it('should not set min_doc_count when mincount is absent', () => {
    const inner = applyBodyHistogram({ field: 'price', start: 0, end: 100, gap: 10 });
    expect(inner.has('min_doc_count')).toBe(false);
  });

  it('should handle mincount of 0', () => {
    const inner = applyBodyHistogram({
      field: 'price',
      start: 0,
      end: 100,
      gap: 10,
      mincount: 0,
    });
    expect(inner.get('min_doc_count')).toBe(0);
  });

  it('should only have field when no optional parameters are provided', () => {
    const inner = applyBodyHistogram({ field: 'price' });
    expect(inner.size).toBe(1);
    expect(inner.get('field')).toBe('price');
  });

  it('should work from a query-string param', () => {
    const ctx = ctxWithParamFacet({ type: 'range', field: 'price', start: 0, end: 50, gap: 5 });
    request.apply(ctx);
    const inner = histogramInner(ctx.body.get('aggs'));
    expect(inner.get('field')).toBe('price');
    expect(inner.get('interval')).toBe(5);
  });
});

// ---------------------------------------------------------------------------
// Arbitrary range facet (ranges array) → OpenSearch range aggregation
// ---------------------------------------------------------------------------

describe('arbitrary range facet conversion (range)', () => {
  it('should produce a range aggregation with field', () => {
    const inner = applyBodyRange({
      field: 'price',
      ranges: ['[0,25)', '[25,50)', '[50,*)'],
    });
    expect(inner.get('field')).toBe('price');
  });

  it('should return a Map with exactly one top-level "range" key', () => {
    const aggs = applyAndGetAggs(
      ctxWithBodyFacet({ type: 'range', field: 'price', ranges: ['[0,25)'] }),
    );
    const agg = aggs.get(FACET_NAME);
    expect(agg.size).toBe(1);
    expect(agg.has('range')).toBe(true);
  });

  describe('string range parsing', () => {
    it('should parse "[0,25)" into from=0, to=25 with key preserved', () => {
      const inner = applyBodyRange({ field: 'price', ranges: ['[0,25)'] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges).toHaveLength(1);
      expect(ranges[0].get('key')).toBe('[0,25)');
      expect(ranges[0].get('from')).toBe(0);
      expect(ranges[0].get('to')).toBe(25);
    });

    it('should parse "[50,*)" as from=50, no to (unbounded upper)', () => {
      const inner = applyBodyRange({ field: 'price', ranges: ['[50,*)'] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges[0].get('from')).toBe(50);
      expect(ranges[0].has('to')).toBe(false);
    });

    it('should parse "[*,25)" as to=25, no from (unbounded lower)', () => {
      const inner = applyBodyRange({ field: 'price', ranges: ['[*,25)'] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges[0].has('from')).toBe(false);
      expect(ranges[0].get('to')).toBe(25);
    });

    it('should handle multiple string ranges', () => {
      const inner = applyBodyRange({
        field: 'price',
        ranges: ['[0,25)', '[25,50)', '[50,100)'],
      });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges).toHaveLength(3);
      expect(ranges[0].get('from')).toBe(0);
      expect(ranges[0].get('to')).toBe(25);
      expect(ranges[1].get('from')).toBe(25);
      expect(ranges[1].get('to')).toBe(50);
      expect(ranges[2].get('from')).toBe(50);
      expect(ranges[2].get('to')).toBe(100);
    });

    it('should handle decimal values in ranges', () => {
      const inner = applyBodyRange({
        field: 'score',
        ranges: ['[0.0,0.5)', '[0.5,1.0)'],
      });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges[0].get('from')).toBe(0);
      expect(ranges[0].get('to')).toBe(0.5);
      expect(ranges[1].get('from')).toBe(0.5);
      expect(ranges[1].get('to')).toBe(1);
    });
  });

  describe('object range items (plain JS objects from query-string)', () => {
    it('should handle objects with range string property', () => {
      const inner = applyBodyRange({
        field: 'price',
        ranges: [{ range: '[0,50)' }, { range: '[50,100)' }],
      });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges).toHaveLength(2);
      expect(ranges[0].get('key')).toBe('[0,50)');
      expect(ranges[0].get('from')).toBe(0);
      expect(ranges[0].get('to')).toBe(50);
    });

    it('should handle objects with from/to/key properties', () => {
      const inner = applyBodyRange({
        field: 'price',
        ranges: [
          { from: 0, to: 50, key: 'cheap' },
          { from: 50, to: 100, key: 'expensive' },
        ],
      });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges).toHaveLength(2);
      expect(ranges[0].get('from')).toBe(0);
      expect(ranges[0].get('to')).toBe(50);
      expect(ranges[0].get('key')).toBe('cheap');
      expect(ranges[1].get('from')).toBe(50);
      expect(ranges[1].get('to')).toBe(100);
      expect(ranges[1].get('key')).toBe('expensive');
    });

    it('should handle objects with only from (unbounded upper)', () => {
      const inner = applyBodyRange({ field: 'price', ranges: [{ from: 100 }] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges[0].get('from')).toBe(100);
      expect(ranges[0].has('to')).toBe(false);
    });

    it('should handle objects with only to (unbounded lower)', () => {
      const inner = applyBodyRange({ field: 'price', ranges: [{ to: 50 }] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges[0].has('from')).toBe(false);
      expect(ranges[0].get('to')).toBe(50);
    });
  });

  describe('Map-like range items (from Jackson / GraalVM interop)', () => {
    it('should handle Map-like items with range string', () => {
      const rangeItem = new Map([['range', '[10,20)']]) as unknown as JavaMap;
      const inner = applyBodyRange({ field: 'price', ranges: [rangeItem] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges).toHaveLength(1);
      expect(ranges[0].get('key')).toBe('[10,20)');
      expect(ranges[0].get('from')).toBe(10);
      expect(ranges[0].get('to')).toBe(20);
    });

    it('should handle Map-like items with from/to/key', () => {
      const rangeItem = new Map<string, any>([
        ['from', 0],
        ['to', 50],
        ['key', 'low'],
      ]) as unknown as JavaMap;
      const inner = applyBodyRange({ field: 'price', ranges: [rangeItem] });
      const ranges = inner.get('ranges') as JavaMap[];
      expect(ranges[0].get('from')).toBe(0);
      expect(ranges[0].get('to')).toBe(50);
      expect(ranges[0].get('key')).toBe('low');
    });
  });

  it('should handle an empty ranges array', () => {
    const inner = applyBodyRange({ field: 'price', ranges: [] });
    const ranges = inner.get('ranges') as JavaMap[];
    expect(ranges).toHaveLength(0);
  });

  it('should work from a query-string param with arbitrary ranges', () => {
    const ctx = ctxWithParamFacet({
      type: 'range',
      field: 'price',
      ranges: [{ range: '[0,50)' }, { range: '[50,*)' }],
    });
    request.apply(ctx);
    const inner = rangeInner(ctx.body.get('aggs'));
    expect(inner.get('field')).toBe('price');
    const ranges = inner.get('ranges') as JavaMap[];
    expect(ranges).toHaveLength(2);
    expect(ranges[0].get('from')).toBe(0);
    expect(ranges[0].get('to')).toBe(50);
    expect(ranges[1].get('from')).toBe(50);
    expect(ranges[1].has('to')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Date uniform range facet (date gap) → OpenSearch date_histogram aggregation
// ---------------------------------------------------------------------------

describe('date uniform range facet conversion (date_histogram)', () => {
  it('should produce a date_histogram with calendar_interval for +1MONTH gap', () => {
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-12-31T00:00:00Z',
      gap: '+1MONTH',
    });
    expect(inner.get('field')).toBe('created_at');
    expect(inner.get('calendar_interval')).toBe('1M');
    expect(inner.has('fixed_interval')).toBe(false);
    expect(inner.has('interval')).toBe(false);
  });

  it('should produce a date_histogram with calendar_interval for +1DAY gap', () => {
    const inner = applyBodyDateHistogram({
      field: 'timestamp',
      start: '2024-01-01T00:00:00Z',
      end: '2024-01-31T00:00:00Z',
      gap: '+1DAY',
    });
    expect(inner.get('calendar_interval')).toBe('1d');
  });

  it('should produce a date_histogram with calendar_interval for +1YEAR gap', () => {
    const inner = applyBodyDateHistogram({
      field: 'timestamp',
      start: '2020-01-01T00:00:00Z',
      end: '2025-01-01T00:00:00Z',
      gap: '+1YEAR',
    });
    expect(inner.get('calendar_interval')).toBe('1y');
  });

  it('should produce a date_histogram with fixed_interval for +5MINUTES gap', () => {
    const inner = applyBodyDateHistogram({
      field: 'event_time',
      start: '2024-01-01T00:00:00Z',
      end: '2024-01-01T01:00:00Z',
      gap: '+5MINUTES',
    });
    expect(inner.get('fixed_interval')).toBe('5m');
    expect(inner.has('calendar_interval')).toBe(false);
  });

  it('should produce a date_histogram with fixed_interval for +3HOURS gap', () => {
    const inner = applyBodyDateHistogram({
      field: 'event_time',
      start: '2024-01-01T00:00:00Z',
      end: '2024-01-02T00:00:00Z',
      gap: '+3HOURS',
    });
    expect(inner.get('fixed_interval')).toBe('3h');
  });

  it('should return a Map with exactly one top-level "date_histogram" key', () => {
    const aggs = applyAndGetAggs(
      ctxWithBodyFacet({
        type: 'range',
        field: 'created_at',
        start: '2024-01-01T00:00:00Z',
        end: '2024-12-31T00:00:00Z',
        gap: '+1MONTH',
      }),
    );
    const agg = aggs.get(FACET_NAME);
    expect(agg.size).toBe(1);
    expect(agg.has('date_histogram')).toBe(true);
    expect(agg.has('histogram')).toBe(false);
  });

  it('should set extended_bounds with min and max (end - 1s) and hard_bounds with original start/end', () => {
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-12-31T00:00:00Z',
      gap: '+1MONTH',
    });
    const extBounds = inner.get('extended_bounds');
    expect(extBounds).toBeDefined();
    expect(extBounds.get('min')).toBe('2024-01-01T00:00:00Z');
    // Solr's end is exclusive — max is set to end - 1s to avoid an extra bucket
    expect(extBounds.get('max')).toBe('2024-12-30T23:59:59Z');

    const hardBounds = inner.get('hard_bounds');
    expect(hardBounds).toBeDefined();
    expect(hardBounds.get('min')).toBe('2024-01-01T00:00:00Z');
    expect(hardBounds.get('max')).toBe('2024-12-31T00:00:00Z');
  });

  it('should set format to strict_date_time_no_millis for ISO date output', () => {
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-12-31T00:00:00Z',
      gap: '+1MONTH',
    });
    expect(inner.get('format')).toBe('strict_date_time_no_millis');
  });

  it('should set only min on extended_bounds and hard_bounds when end is absent', () => {
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      gap: '+1MONTH',
    });
    const extBounds = inner.get('extended_bounds');
    expect(extBounds.get('min')).toBe('2024-01-01T00:00:00Z');
    expect(extBounds.has('max')).toBe(false);

    const hardBounds = inner.get('hard_bounds');
    expect(hardBounds.get('min')).toBe('2024-01-01T00:00:00Z');
    expect(hardBounds.has('max')).toBe(false);
  });

  it('should not set extended_bounds or hard_bounds when start and end are both absent', () => {
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      gap: '+1MONTH',
    });
    expect(inner.has('extended_bounds')).toBe(false);
    expect(inner.has('hard_bounds')).toBe(false);
  });

  it('should map mincount to min_doc_count', () => {
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-12-31T00:00:00Z',
      gap: '+1MONTH',
      mincount: 1,
    });
    expect(inner.get('min_doc_count')).toBe(1);
  });

  it('should still produce numeric histogram for a numeric gap (regression)', () => {
    const inner = applyBodyHistogram({ field: 'price', start: 0, end: 100, gap: 10 });
    expect(inner.get('field')).toBe('price');
    expect(inner.get('interval')).toBe(10);
    expect(inner.has('calendar_interval')).toBe(false);
    expect(inner.has('fixed_interval')).toBe(false);
  });

  it('should work from a query-string param with date gap', () => {
    const ctx = ctxWithParamFacet({
      type: 'range',
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-06-01T00:00:00Z',
      gap: '+1MONTH',
    });
    request.apply(ctx);
    const inner = dateHistogramInner(ctx.body.get('aggs'));
    expect(inner.get('field')).toBe('created_at');
    expect(inner.get('calendar_interval')).toBe('1M');
  });

  it('should warn about unsupported range params on date_histogram too', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-12-31T00:00:00Z',
      gap: '+1MONTH',
      hardend: true,
      include: 'lower',
      other: 'before',
    });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('no direct OpenSearch histogram equivalent'),
    );
    warnSpy.mockRestore();
  });

  it('should produce date_histogram with fixed_interval for compound gap +1MONTH+2DAYS', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const inner = applyBodyDateHistogram({
      field: 'created_at',
      start: '2024-01-01T00:00:00Z',
      end: '2024-12-31T00:00:00Z',
      gap: '+1MONTH+2DAYS',
    });
    expect(inner.get('field')).toBe('created_at');
    // 1 month (720h) + 2 days (48h) = 768h
    expect(inner.get('fixed_interval')).toBe('768h');
    expect(inner.has('calendar_interval')).toBe(false);
    expect(inner.has('interval')).toBe(false);
    warnSpy.mockRestore();
  });
});

// ---------------------------------------------------------------------------
// convertSort — Map input coverage (utils.ts lines 26-30)
// ---------------------------------------------------------------------------

describe('convertSort with Map input', () => {
  it('should convert a Map with count key to _count', () => {
    const sortMap = new Map([['count', 'desc']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('_count')).toBe('desc');
  });

  it('should convert a Map with index key to _key', () => {
    const sortMap = new Map([['index', 'asc']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('_key')).toBe('asc');
  });

  it('should default to desc when Map value is falsy', () => {
    const sortMap = new Map([['count', '']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('_count')).toBe('desc');
  });

  it('should pass through unknown sort keys from a Map', () => {
    const sortMap = new Map([['my_stat', 'ASC']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('my_stat')).toBe('asc');
  });
});

// ---------------------------------------------------------------------------
// Query facet → OpenSearch filter aggregation
// ---------------------------------------------------------------------------

describe('query facet conversion (filter)', () => {
  it('should produce a filter aggregation with a query_string for a free-text query', () => {
    const inner = applyBodyQuery({ q: 'hello world' });
    expect(inner.get('query_string')).toBeDefined();
    expect(inner.get('query_string').get('query')).toBe('hello world');
  });

  it('should produce a filter aggregation with query_string for a Lucene range query', () => {
    const inner = applyBodyQuery({ q: 'popularity:[100 TO *]' });
    expect(inner.get('query_string')).toBeDefined();
    expect(inner.get('query_string').get('query')).toBe('popularity:[100 TO *]');
  });

  it('should return a Map with exactly one top-level "filter" key', () => {
    const aggs = applyAndGetAggs(
      ctxWithBodyFacet({ type: 'query', q: 'status:active' }),
    );
    const agg = aggs.get(FACET_NAME);
    expect(agg.size).toBe(1);
    expect(agg.has('filter')).toBe(true);
  });

  it('should produce match_all for q: "*:*"', () => {
    const inner = applyBodyQuery({ q: '*:*' });
    expect(inner.get('match_all')).toBeDefined();
  });

  it('should produce a query_string for q: "field:value"', () => {
    const inner = applyBodyQuery({ q: 'status:active' });
    expect(inner.get('query_string')).toBeDefined();
    expect(inner.get('query_string').get('query')).toBe('status:active');
  });

  it('should default to match_all when q is absent', () => {
    const inner = applyBodyQuery({});
    expect(inner.get('match_all')).toBeDefined();
  });

  it('should work from a query-string param', () => {
    const ctx = ctxWithParamFacet({ type: 'query', q: 'hello world' });
    request.apply(ctx);
    const inner = filterInner(ctx.body.get('aggs'));
    expect(inner.get('query_string')).toBeDefined();
    expect(inner.get('query_string').get('query')).toBe('hello world');
  });

  it('should warn about unknown keys in a query facet definition', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    applyBodyQuery({ q: '*:*', unknownParam: 'bar' });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('Unprocessed keys in query facet'),
    );
    warnSpy.mockRestore();
  });
});

// ---------------------------------------------------------------------------
// Stat (metric) facets — string shorthand like "avg(price)"
// ---------------------------------------------------------------------------

/** Helper: build a context with a string stat facet and return the agg result. */
function applyStatFacet(expr: string): JavaMap {
  const jsonFacet = new Map([[FACET_NAME, expr]]) as unknown as JavaMap;
  const body = new Map([['json.facet', jsonFacet]]) as unknown as JavaMap;
  const ctx: RequestContext = {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams(),
    body,
    emitMetric: () => {},
    _metrics: new Map(),
  };
  request.apply(ctx);
  return ctx.body.get('aggs').get(FACET_NAME);
}

describe('stat facet conversion', () => {
  it('should convert avg(price) to avg aggregation', () => {
    const agg = applyStatFacet('avg(price)');
    expect(agg.get('avg')).toEqual(new Map([['field', 'price']]));
  });

  it('should convert sum(revenue) to sum aggregation', () => {
    const agg = applyStatFacet('sum(revenue)');
    expect(agg.get('sum')).toEqual(new Map([['field', 'revenue']]));
  });

  it('should convert min(price) to min aggregation', () => {
    const agg = applyStatFacet('min(price)');
    expect(agg.get('min')).toEqual(new Map([['field', 'price']]));
  });

  it('should convert max(price) to max aggregation', () => {
    const agg = applyStatFacet('max(price)');
    expect(agg.get('max')).toEqual(new Map([['field', 'price']]));
  });

  it('should convert unique(author) to cardinality aggregation', () => {
    const agg = applyStatFacet('unique(author)');
    expect(agg.get('cardinality')).toEqual(new Map([['field', 'author']]));
  });

  it('should convert hll(author) to cardinality aggregation', () => {
    const agg = applyStatFacet('hll(author)');
    expect(agg.get('cardinality')).toEqual(new Map([['field', 'author']]));
  });

  it('should convert countvals(status) to value_count aggregation', () => {
    const agg = applyStatFacet('countvals(status)');
    expect(agg.get('value_count')).toEqual(new Map([['field', 'status']]));
  });

  it('should convert count(*) to value_count on _id', () => {
    const agg = applyStatFacet('count(*)');
    expect(agg.get('value_count')).toEqual(new Map([['field', '_id']]));
  });

  it('should throw for unsupported stat function', () => {
    expect(() => applyStatFacet('sumsq(price)')).toThrow("Unsupported stat function 'sumsq'");
  });

  it('should throw for stat function with wrong number of args', () => {
    expect(() => applyStatFacet('avg(price, weight)')).toThrow('expects exactly 1 argument, got 2');
  });

  it('should throw for invalid stat expression', () => {
    expect(() => applyStatFacet('not-a-function')).toThrow();
  });

  it('should work as nested sub-facet inside terms facet', () => {
    const def = new Map([
      ['type', 'terms'],
      ['field', 'category'],
      ['facet', new Map([['avg_price', 'avg(price)']])],
    ]) as unknown as JavaMap;
    const jsonFacet = new Map([[FACET_NAME, def]]) as unknown as JavaMap;
    const body = new Map([['json.facet', jsonFacet]]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg: new Map() as unknown as JavaMap,
      endpoint: 'select',
      collection: 'testcollection',
      params: new URLSearchParams(),
      body,
      emitMetric: () => {},
      _metrics: new Map(),
    };
    request.apply(ctx);
    const facetAgg = ctx.body.get('aggs').get(FACET_NAME);
    const nestedAggs = facetAgg.get('aggs');
    expect(nestedAggs.get('avg_price').get('avg')).toEqual(new Map([['field', 'price']]));
  });
});

// ---------------------------------------------------------------------------
// Edge cases and warning paths (json-facets.ts coverage gaps)
// ---------------------------------------------------------------------------

describe('edge cases and warning paths', () => {
  it('should return key-only map for an invalid range string that does not match regex', () => {
    const inner = applyBodyRange({ field: 'price', ranges: ['not-a-range'] });
    const ranges = inner.get('ranges') as JavaMap[];
    expect(ranges).toHaveLength(1);
    expect(ranges[0].get('key')).toBe('not-a-range');
    expect(ranges[0].has('from')).toBe(false);
    expect(ranges[0].has('to')).toBe(false);
  });

  it('should warn for non-standard boundary semantics like (10,20]', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const inner = applyBodyRange({ field: 'price', ranges: ['(10,20]'] });
    const ranges = inner.get('ranges') as JavaMap[];
    expect(ranges[0].get('from')).toBe(10);
    expect(ranges[0].get('to')).toBe(20);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('may not map exactly'),
    );
    warnSpy.mockRestore();
  });

  it('should skip unrecognised range item types (e.g. number) and warn', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const inner = applyBodyRange({ field: 'price', ranges: [42] });
    const ranges = inner.get('ranges') as JavaMap[];
    expect(ranges).toHaveLength(0);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('Skipping unrecognised range item'),
    );
    warnSpy.mockRestore();
  });

  it('should warn about unknown keys in a terms facet definition', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    applyBodyTerms({ field: 'category', unknownParam: 'foo' });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('Unprocessed keys in terms facet'),
    );
    warnSpy.mockRestore();
  });

  it('should warn about unsupported range params (hardend, include, other)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    applyBodyHistogram({
      field: 'price',
      start: 0,
      end: 100,
      gap: 10,
      hardend: true,
      include: 'lower',
      other: 'before',
    });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('no direct OpenSearch histogram equivalent'),
    );
    warnSpy.mockRestore();
  });

  it('should warn about unknown keys in a range facet definition', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    applyBodyHistogram({
      field: 'price',
      start: 0,
      end: 100,
      gap: 10,
      customUnknown: true,
    });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('Unprocessed keys in range facet'),
    );
    warnSpy.mockRestore();
  });

  it('should throw for an unimplemented facet type', () => {
    expect(() => {
      applyAndGetAggs(ctxWithBodyFacet({ type: 'heatmap', field: 'x' }));
    }).toThrow("Facet type 'heatmap' is not implemented");
  });

  it('should return empty map for non-map facet definition', () => {
    // Build a context where the facet value is neither a Map nor a string
    const jsonFacet = new Map([[FACET_NAME, 42]]) as unknown as JavaMap;
    const body = new Map([['json.facet', jsonFacet]]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg: new Map() as unknown as JavaMap,
      endpoint: 'select',
      collection: 'testcollection',
      params: new URLSearchParams(),
      body,
      emitMetric: () => {},
      _metrics: new Map(),
    };
    request.apply(ctx);
    const agg = ctx.body.get('aggs').get(FACET_NAME);
    expect(agg.size).toBe(0);
  });

  it('should not set aggs when json.facet body map is empty', () => {
    const jsonFacet = new Map() as unknown as JavaMap;
    const body = new Map([['json.facet', jsonFacet]]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg: new Map() as unknown as JavaMap,
      endpoint: 'select',
      collection: 'testcollection',
      params: new URLSearchParams(),
      body,
      emitMetric: () => {},
      _metrics: new Map(),
    };
    request.apply(ctx);
    expect(ctx.body.has('aggs')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Nested facets (sub-facets via `facet` key)
// ---------------------------------------------------------------------------

describe('nested facets (sub-facets)', () => {
  describe('terms facet with nested sub-facets', () => {
    it('should produce nested aggs for a terms facet with a nested terms sub-facet', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'terms',
          field: 'category',
          facet: new Map<string, any>([
            ['brands', new Map<string, any>([
              ['type', 'terms'],
              ['field', 'brand'],
              ['limit', 5],
            ])],
          ]),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('terms')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(true);

      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('brands')).toBe(true);

      const brandsAgg = subAggs.get('brands');
      expect(brandsAgg.has('terms')).toBe(true);
      expect(brandsAgg.get('terms').get('field')).toBe('brand');
      expect(brandsAgg.get('terms').get('size')).toBe(5);
    });

    it('should not set aggs when facet sub-map is empty', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'terms',
          field: 'category',
          facet: new Map(),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('terms')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(false);
    });

    it('should not set aggs when facet key is absent', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({ type: 'terms', field: 'category' }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('aggs')).toBe(false);
    });

    it('should not warn about facet as an unknown key', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'terms',
          field: 'category',
          facet: new Map([
            ['sub', new Map([['type', 'terms'], ['field', 'brand']])],
          ]),
        }),
      );
      for (const call of warnSpy.mock.calls) {
        expect(call[0]).not.toContain('Unprocessed keys');
      }
      warnSpy.mockRestore();
    });
  });

  describe('range facet (histogram) with nested sub-facets', () => {
    it('should produce nested aggs for a histogram facet with a nested terms sub-facet', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'range',
          field: 'price',
          start: 0,
          end: 100,
          gap: 25,
          facet: new Map([
            ['top_brands', new Map([
              ['type', 'terms'],
              ['field', 'brand'],
            ])],
          ]),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('histogram')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(true);

      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('top_brands')).toBe(true);
      expect(subAggs.get('top_brands').get('terms').get('field')).toBe('brand');
    });
  });

  describe('range facet (arbitrary ranges) with nested sub-facets', () => {
    it('should produce nested aggs for an arbitrary range facet with a nested terms sub-facet', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'range',
          field: 'price',
          ranges: ['[0,50)', '[50,100)'],
          facet: new Map([
            ['sellers', new Map([
              ['type', 'terms'],
              ['field', 'seller'],
            ])],
          ]),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('range')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(true);

      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('sellers')).toBe(true);
      expect(subAggs.get('sellers').get('terms').get('field')).toBe('seller');
    });
  });

  describe('range facet (date_histogram) with nested sub-facets', () => {
    it('should produce nested aggs for a date_histogram facet with a nested terms sub-facet', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'range',
          field: 'created_at',
          start: '2024-01-01T00:00:00Z',
          end: '2024-12-31T00:00:00Z',
          gap: '+1MONTH',
          facet: new Map([
            ['authors', new Map([
              ['type', 'terms'],
              ['field', 'author'],
            ])],
          ]),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('date_histogram')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(true);

      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('authors')).toBe(true);
      expect(subAggs.get('authors').get('terms').get('field')).toBe('author');
    });
  });

  describe('query facet with nested sub-facets', () => {
    it('should produce nested aggs for a query facet with a nested terms sub-facet', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'query',
          q: 'status:active',
          facet: new Map([
            ['categories', new Map([
              ['type', 'terms'],
              ['field', 'category'],
            ])],
          ]),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      expect(outerAgg.has('filter')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(true);

      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('categories')).toBe(true);
      expect(subAggs.get('categories').get('terms').get('field')).toBe('category');
    });
  });

  describe('multi-level nesting', () => {
    it('should handle three levels of nested facets', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'terms',
          field: 'category',
          facet: new Map([
            ['brands', new Map<string, any>([
              ['type', 'terms'],
              ['field', 'brand'],
              ['facet', new Map([
                ['price_ranges', new Map<string, any>([
                  ['type', 'range'],
                  ['field', 'price'],
                  ['start', 0],
                  ['end', 100],
                  ['gap', 25],
                ])],
              ])],
            ])],
          ]),
        }),
      );

      // Level 1: category terms
      const level1 = aggs.get(FACET_NAME);
      expect(level1.has('terms')).toBe(true);
      expect(level1.has('aggs')).toBe(true);

      // Level 2: brand terms
      const level2 = level1.get('aggs').get('brands');
      expect(level2.has('terms')).toBe(true);
      expect(level2.get('terms').get('field')).toBe('brand');
      expect(level2.has('aggs')).toBe(true);

      // Level 3: price histogram
      const level3 = level2.get('aggs').get('price_ranges');
      expect(level3.has('histogram')).toBe(true);
      expect(level3.get('histogram').get('field')).toBe('price');
      expect(level3.has('aggs')).toBe(false);
    });

    it('should handle multiple sibling sub-facets', () => {
      const aggs = applyAndGetAggs(
        ctxWithBodyFacet({
          type: 'terms',
          field: 'category',
          facet: new Map([
            ['brands', new Map([
              ['type', 'terms'],
              ['field', 'brand'],
            ])],
            ['price_ranges', new Map<string, any>([
              ['type', 'range'],
              ['field', 'price'],
              ['start', 0],
              ['end', 100],
              ['gap', 25],
            ])],
          ]),
        }),
      );

      const outerAgg = aggs.get(FACET_NAME);
      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('brands')).toBe(true);
      expect(subAggs.has('price_ranges')).toBe(true);
      expect(subAggs.get('brands').get('terms').get('field')).toBe('brand');
      expect(subAggs.get('price_ranges').get('histogram').get('field')).toBe('price');
    });
  });

  describe('nested facets from query-string param', () => {
    it('should handle nested facets parsed from a JSON query-string param', () => {
      const ctx = ctxWithParamFacet({
        type: 'terms',
        field: 'category',
        facet: {
          brands: {
            type: 'terms',
            field: 'brand',
            limit: 3,
          },
        },
      });
      request.apply(ctx);

      const outerAgg = ctx.body.get('aggs').get(FACET_NAME);
      expect(outerAgg.has('terms')).toBe(true);
      expect(outerAgg.has('aggs')).toBe(true);

      const subAggs = outerAgg.get('aggs');
      expect(subAggs.has('brands')).toBe(true);
      expect(subAggs.get('brands').get('terms').get('field')).toBe('brand');
      expect(subAggs.get('brands').get('terms').get('size')).toBe(3);
    });
  });
});

function ctxWithSpy(obj: Record<string, any>): { ctx: RequestContext; spy: ReturnType<typeof vi.fn> } {
  const def = new Map(Object.entries(obj)) as unknown as JavaMap;
  const jsonFacet = new Map([[FACET_NAME, def]]) as unknown as JavaMap;
  const body = new Map([['json.facet', jsonFacet]]) as unknown as JavaMap;
  const spy = vi.fn();
  const ctx: RequestContext = {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams(),
    body,
    emitMetric: spy,
    _metrics: new Map(),
  };
  return { ctx, spy };
}

describe('limitation metrics emission', () => {

  it('should emit terms_offset when offset > 0', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'terms', field: 'color', offset: 5 });
    request.apply(ctx);
    expect(spy).toHaveBeenCalledWith('terms_offset');
  });

  it('should not emit terms_offset when offset is 0', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'terms', field: 'color', offset: 0 });
    request.apply(ctx);
    expect(spy).not.toHaveBeenCalledWith('terms_offset');
  });

  it('should emit date_range_gap for approximated single-unit gap', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'range', field: 'date', start: '2020-01-01T00:00:00Z', end: '2021-01-01T00:00:00Z', gap: '+2MONTHS' });
    request.apply(ctx);
    expect(spy).toHaveBeenCalledWith('date_range_gap');
  });

  it('should emit date_range_gap_compound for compound gap', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'range', field: 'date', start: '2020-01-01T00:00:00Z', end: '2021-01-01T00:00:00Z', gap: '+1MONTH+2DAYS' });
    request.apply(ctx);
    expect(spy).toHaveBeenCalledWith('date_range_gap_compound');
  });

  it('should not emit date_range_gap for exact calendar_interval', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'range', field: 'date', start: '2020-01-01T00:00:00Z', end: '2021-01-01T00:00:00Z', gap: '+1MONTH' });
    request.apply(ctx);
    expect(spy).not.toHaveBeenCalledWith('date_range_gap');
    expect(spy).not.toHaveBeenCalledWith('date_range_gap_compound');
  });

  it('should emit range_boundary for non-standard bracket semantics', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'range', field: 'price', ranges: ['(10,20]'] });
    request.apply(ctx);
    expect(spy).toHaveBeenCalledWith('range_boundary');
  });

  it('should not emit range_boundary for standard [from,to) brackets', () => {
    const { ctx, spy } = ctxWithSpy({ type: 'range', field: 'price', ranges: ['[10,20)'] });
    request.apply(ctx);
    expect(spy).not.toHaveBeenCalledWith('range_boundary');
  });
});
