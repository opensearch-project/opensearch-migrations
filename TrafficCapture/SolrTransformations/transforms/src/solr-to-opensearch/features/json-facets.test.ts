import { describe, it, expect } from 'vitest';
import { request } from './json-facets';
import type { RequestContext, JavaMap } from '../context';

const FACET_NAME = 'myFacet';

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

/** Shortcut: apply the transform to a body-based context and return the inner terms map. */
function applyBodyFacet(obj: Record<string, any>): JavaMap {
  return termsInner(applyAndGetAggs(ctxWithBodyFacet(obj)));
}

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

  describe('terms facet conversion', () => {
    it('should set the field on the terms aggregation', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
      expect(inner.get('field')).toBe('category');
    });

    it('should map limit to size', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', limit: 5 });
      expect(inner.get('size')).toBe(5);
    });

    it('should not set size when limit is absent', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
      expect(inner.has('size')).toBe(false);
    });

    it('should map offset to shard_size', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', offset: 10 });
      expect(inner.get('shard_size')).toBe(10);
    });

    it('should not set shard_size when offset is absent', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
      expect(inner.has('shard_size')).toBe(false);
    });

    it('should map mincount to min_doc_count', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', mincount: 2 });
      expect(inner.get('min_doc_count')).toBe(2);
    });

    it('should not set min_doc_count when mincount is absent', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
      expect(inner.has('min_doc_count')).toBe(false);
    });

    it('should map prefix to an include regex pattern', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', prefix: 'foo' });
      expect(inner.get('include')).toBe('foo.*');
    });

    it('should not set include when prefix is absent', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
      expect(inner.has('include')).toBe(false);
    });

    it('should set missing to empty string when missing is true', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', missing: true });
      expect(inner.get('missing')).toBe('');
    });

    it('should not set missing when missing is false', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', missing: false });
      expect(inner.has('missing')).toBe(false);
    });

    it('should not set missing when missing is absent', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
      expect(inner.has('missing')).toBe(false);
    });

    describe('sort conversion', () => {
      it('should map "count desc" to order {_count: "desc"}', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category', sort: 'count desc' });
        expect(inner.get('order').get('_count')).toBe('desc');
      });

      it('should map "count asc" to order {_count: "asc"}', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category', sort: 'count asc' });
        expect(inner.get('order').get('_count')).toBe('asc');
      });

      it('should map "index asc" to order {_key: "asc"}', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category', sort: 'index asc' });
        expect(inner.get('order').get('_key')).toBe('asc');
      });

      it('should map "index desc" to order {_key: "desc"}', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category', sort: 'index desc' });
        expect(inner.get('order').get('_key')).toBe('desc');
      });

      it('should default sort direction to desc when direction is omitted', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category', sort: 'count' });
        expect(inner.get('order').get('_count')).toBe('desc');
      });

      it('should pass through unknown sort keys as-is', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category', sort: 'my_metric asc' });
        expect(inner.get('order').get('my_metric')).toBe('asc');
      });

      it('should not set order when sort is absent', () => {
        const inner = applyBodyFacet({ type: 'terms', field: 'category' });
        expect(inner.has('order')).toBe(false);
      });
    });

    it('should handle limit of 0', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', limit: 0 });
      expect(inner.get('size')).toBe(0);
    });

    it('should handle mincount of 0', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category', mincount: 0 });
      expect(inner.get('min_doc_count')).toBe(0);
    });

    it('should handle all options combined', () => {
      const inner = applyBodyFacet({
        type: 'terms',
        field: 'status',
        limit: 20,
        offset: 5,
        mincount: 1,
        prefix: 'active',
        missing: true,
        sort: 'index asc',
      });

      expect(inner.get('field')).toBe('status');
      expect(inner.get('size')).toBe(20);
      expect(inner.get('shard_size')).toBe(5);
      expect(inner.get('min_doc_count')).toBe(1);
      expect(inner.get('include')).toBe('active.*');
      expect(inner.get('missing')).toBe('');
      expect(inner.get('order').get('_key')).toBe('asc');
    });

    it('should only have field when no optional properties are provided', () => {
      const inner = applyBodyFacet({ type: 'terms', field: 'category' });
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
});
