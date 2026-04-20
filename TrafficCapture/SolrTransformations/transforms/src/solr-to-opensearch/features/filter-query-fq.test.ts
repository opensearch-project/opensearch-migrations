import { describe, it, expect } from 'vitest';
import { request } from './filter-query-fq';
import type { RequestContext, JavaMap } from '../context';

/** Create a mock RequestContext for testing. */
function createMockContext(
  params: Record<string, string | string[]>,
  existingQuery?: Map<string, any>,
): RequestContext {
  const body = new Map<string, any>();
  if (existingQuery) {
    body.set('query', existingQuery);
  }

  // Build URLSearchParams supporting multiple values for same key
  const searchParams = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) {
      for (const v of value) {
        searchParams.append(key, v);
      }
    } else {
      searchParams.append(key, value);
    }
  }

  return {
    msg: new Map() as JavaMap,
    endpoint: 'select',
    collection: 'test',
    params: searchParams,
    body: body as JavaMap,
  };
}

/** Helper to convert nested Maps to plain objects for easier assertion. */
function mapToObject(map: Map<string, any>): any {
  const obj: any = {};
  for (const [key, value] of map.entries()) {
    if (value instanceof Map) {
      obj[key] = mapToObject(value);
    } else if (Array.isArray(value)) {
      obj[key] = value.map((v) => (v instanceof Map ? mapToObject(v) : v));
    } else {
      obj[key] = value;
    }
  }
  return obj;
}

describe('filter-query-fq request transform', () => {
  it('has correct name', () => {
    expect(request.name).toBe('filter-query-fq');
  });

  describe('match function', () => {
    it('matches when fq param is present', () => {
      const ctx = createMockContext({ fq: 'status:active' });
      expect(request.match?.(ctx)).toBe(true);
    });

    it('matches when multiple fq params are present', () => {
      const ctx = createMockContext({ fq: ['status:active', 'category:electronics', 'price:[10 TO 100]'] });
      expect(request.match?.(ctx)).toBe(true);
    });

    it('does not match when fq param is absent', () => {
      const ctx = createMockContext({ q: 'test' });
      expect(request.match?.(ctx)).toBe(false);
    });
  });

  describe('single fq', () => {
    it('wraps existing query in bool.must and adds filter', () => {
      const existingQuery = new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]);
      const ctx = createMockContext({ fq: 'status:published' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool).toBeDefined();
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.filter).toHaveLength(1);
    });

    it('transforms field:value filter correctly', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext({ fq: 'status:active' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter[0].match.status.query).toBe('active');
    });

    it('transforms range filter correctly', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext({ fq: 'price:[10 TO 100]' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter[0].range.price.gte).toBe('10');
      expect(result.bool.filter[0].range.price.lte).toBe('100');
    });
  });

  describe('multiple fq', () => {
    it('adds all fq values to filter array', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: ['status:active', 'type:book'] },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(2);
    });

    it('preserves order of multiple fq values', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: ['status:active', 'price:[10 TO 100]', 'category:tech'] },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(3);
      expect(result.bool.filter[0].match.status.query).toBe('active');
      expect(result.bool.filter[1].range.price).toBeDefined();
      expect(result.bool.filter[2].match.category.query).toBe('tech');
    });

    it('handles mixed filter types across multiple fq params', () => {
      const existingQuery = new Map([['match', new Map([['title', new Map([['query', 'laptop']])]])]]);
      const ctx = createMockContext(
        { 
          fq: [
            'category:electronics',
            'price:[100 TO 1000]',
            'title:"gaming laptop"',
            'status:active',
          ] 
        },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.filter).toHaveLength(4);
      // Field filter
      expect(result.bool.filter[0].match.category.query).toBe('electronics');
      // Range filter
      expect(result.bool.filter[1].range.price.gte).toBe('100');
      expect(result.bool.filter[1].range.price.lte).toBe('1000');
      // Phrase filter
      expect(result.bool.filter[2].match_phrase.title.query).toBe('gaming laptop');
      // Another field filter
      expect(result.bool.filter[3].match.status.query).toBe('active');
    });
  });

  describe('fq without existing query', () => {
    it('creates bool with only filter when no existing query', () => {
      const ctx = createMockContext({ fq: 'status:active' });

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool).toBeDefined();
      expect(result.bool.must).toBeUndefined();
      expect(result.bool.filter).toHaveLength(1);
    });
  });

  describe('empty and whitespace fq values', () => {
    it('skips empty fq value', () => {
      const ctx = createMockContext({ fq: '' });

      request.apply(ctx);

      expect(ctx.body.get('query')).toBeUndefined();
    });

    it('skips whitespace-only fq value', () => {
      const ctx = createMockContext({ fq: '   ' });

      request.apply(ctx);

      expect(ctx.body.get('query')).toBeUndefined();
    });

    it('filters out empty values from multiple fq params', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: ['status:active', '', '   ', 'type:book'] },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(2);
      expect(result.bool.filter[0].match.status.query).toBe('active');
      expect(result.bool.filter[1].match.type.query).toBe('book');
    });

    it('returns early when all fq values are empty/whitespace', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: ['', '   ', '\t'] },
        existingQuery,
      );

      request.apply(ctx);

      // Existing query should remain unchanged
      expect(ctx.body.get('query')).toBe(existingQuery);
    });
  });

  describe('complex fq expressions', () => {
    it('handles boolean expressions in fq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: 'status:active AND type:book' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(1);
      // The fq itself becomes a bool query with AND clauses in must
      expect(result.bool.filter[0]).toEqual({
        bool: {
          must: [
            { match: { status: { query: 'active' } } },
            { match: { type: { query: 'book' } } },
          ],
        },
      });
    });

    it('handles phrase filter', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext({ fq: 'title:"hello world"' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter[0].match_phrase.title.query).toBe('hello world');
    });

    it('handles match_all filter (*:*)', () => {
      const existingQuery = new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]);
      const ctx = createMockContext({ fq: '*:*' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter[0].match_all).toBeDefined();
    });
  });

  describe('filter() inline caching syntax in fq', () => {
    /**
     * Solr allows filter(condition) syntax inside fq to cache clauses individually.
     * This enables union of cached filter queries.
     * See: https://solr.apache.org/guide/solr/latest/query-guide/common-query-parameters.html#fq-filter-query-parameter
     */
    it('handles filter() syntax in fq for individual clause caching', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: 'filter(inStock:true) AND filter(price:[10 TO 100])' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(1);
      // The fq contains a bool with two required filter() clauses
      expect(result.bool.filter[0].bool.must).toHaveLength(2);
      // Each filter() wraps its clause in bool.filter
      expect(result.bool.filter[0].bool.must[0].bool.filter).toBeDefined();
      expect(result.bool.filter[0].bool.must[1].bool.filter).toBeDefined();
    });

    it('handles negated filter() in fq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: '-filter(inStock:true)' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(1);
      // Negated filter becomes bool.must_not
      expect(result.bool.filter[0].bool.must_not).toBeDefined();
    });

    it('handles union of filter() clauses with OR', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: 'filter(category:books) OR filter(category:electronics)' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(1);
      // OR produces bool.should
      expect(result.bool.filter[0].bool.should).toHaveLength(2);
    });

    it('handles simple filter() in fq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: 'filter(status:active)' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.filter).toHaveLength(1);
      // filter() wraps in bool.filter
      expect(result.bool.filter[0].bool.filter).toBeDefined();
      expect(result.bool.filter[0].bool.filter[0].match.status.query).toBe('active');
    });
  });

  describe('bool query flattening', () => {
    it('flattens when existing query is a bool query', () => {
      // Existing bool query with must clause
      const existingBool = new Map([
        ['must', [new Map([['match', new Map([['title', new Map([['query', 'java']])]])]])]],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      const ctx = createMockContext({ fq: 'status:active' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      // Should be flattened - single bool with must and filter at same level
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.filter).toHaveLength(1);
      // No nested bool wrapping the original query
      expect(result.bool.must[0].match.title.query).toBe('java');
    });

    it('preserves existing bool clauses when flattening', () => {
      // Existing bool with must, should, and must_not
      const existingBool = new Map([
        ['must', [new Map([['match', new Map([['title', new Map([['query', 'java']])]])]])]],
        ['should', [new Map([['match', new Map([['category', new Map([['query', 'tech']])]])]])]],
        ['must_not', [new Map([['match', new Map([['status', new Map([['query', 'draft']])]])]])]],
        ['minimum_should_match', 1],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      const ctx = createMockContext({ fq: 'inStock:true' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.must_not).toHaveLength(1);
      expect(result.bool.minimum_should_match).toBe(1);
      expect(result.bool.filter).toHaveLength(1);
    });

    it('merges fq filters with existing bool filters', () => {
      // Existing bool with filter clause
      const existingBool = new Map([
        ['must', [new Map([['match', new Map([['title', new Map([['query', 'java']])]])]])]],
        ['filter', [new Map([['match', new Map([['type', new Map([['query', 'book']])]])]])]],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      const ctx = createMockContext({ fq: 'status:active' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      // Should have both existing filter and new fq filter
      expect(result.bool.filter).toHaveLength(2);
      expect(result.bool.filter[0].match.type.query).toBe('book');
      expect(result.bool.filter[1].match.status.query).toBe('active');
    });

    it('wraps non-bool query in must (no flattening)', () => {
      // Non-bool query (simple match)
      const existingQuery = new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]);
      const ctx = createMockContext({ fq: 'status:active' }, existingQuery);

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      // Should wrap in must since it's not a bool
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.must[0].match.title.query).toBe('java');
      expect(result.bool.filter).toHaveLength(1);
    });

    it('merges multiple fq values with existing bool filters', () => {
      // Existing bool with filter clause already present
      const existingBool = new Map<string, any>([
        ['must', [new Map([['match', new Map([['title', new Map([['query', 'java']])]])]])]],
        ['filter', [
          new Map([['match', new Map([['type', new Map([['query', 'book']])]])]]),
          new Map([['range', new Map([['year', new Map([['gte', '2020']])]])]]),
        ]],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      // Multiple fq values to merge
      const ctx = createMockContext(
        { fq: ['status:active', 'inStock:true', 'price:[10 TO 100]'] },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      // Should have 2 existing filters + 3 new fq filters = 5 total
      expect(result.bool.filter).toHaveLength(5);
      // Existing filters preserved in order
      expect(result.bool.filter[0].match.type.query).toBe('book');
      expect(result.bool.filter[1].range.year.gte).toBe('2020');
      // New fq filters appended
      expect(result.bool.filter[2].match.status.query).toBe('active');
      expect(result.bool.filter[3].match.inStock.query).toBe('true');
      expect(result.bool.filter[4].range.price.gte).toBe('10');
      expect(result.bool.filter[4].range.price.lte).toBe('100');
    });
  });

  describe('no-op scenarios', () => {
    it('does not modify context when match passes but all fq values are empty', () => {
      // fq param exists (match passes) but all values are empty/whitespace (apply skips)
      const existingQuery = new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]);
      const ctx = createMockContext({ fq: ['', '   ', '\t\n'] }, existingQuery);

      // Verify match passes (fq param exists)
      expect(request.match?.(ctx)).toBe(true);

      request.apply(ctx);

      // Query should remain unchanged - no bool wrapper added
      const result = ctx.body.get('query');
      expect(result).toBe(existingQuery);
      expect(mapToObject(result).match.title.query).toBe('java');
    });

    it('does not create query when no existing query and all fq values are empty', () => {
      // No existing query, fq param exists but all values empty
      const ctx = createMockContext({ fq: ['', '  '] });

      expect(request.match?.(ctx)).toBe(true);

      request.apply(ctx);

      // No query should be created
      expect(ctx.body.get('query')).toBeUndefined();
    });

    it('filters empty fq values while processing valid ones', () => {
      // Mix of empty and valid fq values - empty ones should be filtered out
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createMockContext(
        { fq: ['', 'status:active', '   ', 'type:book', '\t'] },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObject(ctx.body.get('query'));
      // Only 2 valid filters should be added (empty ones filtered out)
      expect(result.bool.filter).toHaveLength(2);
      expect(result.bool.filter[0].match.status.query).toBe('active');
      expect(result.bool.filter[1].match.type.query).toBe('book');
    });
  });
});
