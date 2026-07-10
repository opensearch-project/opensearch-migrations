import { describe, it, expect } from 'vitest';
import { request } from './boost-query-bq';
import type { RequestContext, JavaMap } from '../context';

/** Create a mock RequestContext for testing. */
function createCtx(
  params: Record<string, string | string[]>,
  existingQuery?: Map<string, any>,
): RequestContext {
  const body = new Map<string, any>();
  if (existingQuery) body.set('query', existingQuery);

  const searchParams = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) {
      for (const v of value) searchParams.append(key, v);
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

/** Convert nested Maps to plain objects for easier assertion. */
function mapToObj(map: Map<string, any>): any {
  const obj: any = {};
  for (const [key, value] of map.entries()) {
    if (value instanceof Map) {
      obj[key] = mapToObj(value);
    } else if (Array.isArray(value)) {
      obj[key] = value.map((v) => (v instanceof Map ? mapToObj(v) : v));
    } else {
      obj[key] = value;
    }
  }
  return obj;
}

describe('boost-query-bq request transform', () => {
  it('has correct name', () => {
    expect(request.name).toBe('boost-query-bq');
  });

  // --- match guard ---

  describe('match', () => {
    it('matches when bq is present', () => {
      const ctx = createCtx({ bq: 'category:food', defType: 'edismax' });
      expect(request.match!(ctx)).toBe(true);
    });

    it('does not match when bq is absent', () => {
      const ctx = createCtx({ defType: 'edismax' });
      expect(request.match!(ctx)).toBe(false);
    });

    it('matches when bq is empty string (apply handles the no-op)', () => {
      const ctx = createCtx({ bq: '', defType: 'edismax' });
      expect(request.match!(ctx)).toBe(true);
    });
  });

  // --- fail-fast on invalid defType ---

  describe('defType validation', () => {
    it('throws when defType is absent', () => {
      const ctx = createCtx({ bq: 'category:food' }, new Map([['match_all', new Map()]]));
      expect(() => request.apply(ctx)).toThrow('[boost-query-bq] bq parameter requires defType=edismax');
    });

    it('throws when defType is lucene', () => {
      const ctx = createCtx({ bq: 'category:food', defType: 'lucene' }, new Map([['match_all', new Map()]]));
      expect(() => request.apply(ctx)).toThrow("got 'lucene'");
    });

    it('throws for defType=dismax', () => {
      const ctx = createCtx({ bq: 'category:food', defType: 'dismax' }, new Map([['match_all', new Map()]]));
      expect(() => request.apply(ctx)).toThrow("got 'dismax'");
    });

    it('does not throw for defType=edismax', () => {
      const ctx = createCtx({ bq: 'category:food', defType: 'edismax' }, new Map([['match_all', new Map()]]));
      expect(() => request.apply(ctx)).not.toThrow();
    });
  });

  // --- single bq ---

  describe('single bq', () => {
    it('wraps existing query in bool.must and adds bq to bool.should', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'category:food', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.must[0].match_all).toBeDefined();
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.should[0].match.category.query).toBe('food');
      expect(result.bool.minimum_should_match).toBeUndefined();
    });

    it('handles boosted bq value', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'category:food^10', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.should[0].match.category.query).toBe('food');
      expect(result.bool.should[0].match.category.boost).toBe(10);
    });

    it('handles phrase bq value', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'title:"aged cheese"', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match_phrase.title.query).toBe('aged cheese');
    });

    it('handles range bq value', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'price:[10 TO 100]', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].range.price.gte).toBe('10');
      expect(result.bool.should[0].range.price.lte).toBe('100');
    });
  });

  // --- multiple bq ---

  describe('multiple bq', () => {
    it('adds all bq values as separate should clauses', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx(
        { bq: ['category:food^10', 'category:deli^5'], defType: 'edismax' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(2);
      expect(result.bool.should[0].match.category.query).toBe('food');
      expect(result.bool.should[0].match.category.boost).toBe(10);
      expect(result.bool.should[1].match.category.query).toBe('deli');
      expect(result.bool.should[1].match.category.boost).toBe(5);
    });

    it('filters out empty bq values', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx(
        { bq: ['category:food', '', '   ', 'type:book'], defType: 'edismax' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(2);
    });

    it('returns early when all bq values are empty', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: ['', '   '], defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      expect(ctx.body.get('query')).toBe(existingQuery);
      expect(ctx.body.size).toBe(1);
      expect(existingQuery.size).toBe(1);
    });
  });

  // --- bool query flattening ---

  describe('bool query flattening', () => {
    it('flattens into existing bool query', () => {
      const existingBool = new Map<string, any>([
        ['must', [new Map([['match', new Map([['title', new Map([['query', 'java']])]])]])]],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      const ctx = createCtx({ bq: 'category:tech^5', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.must[0].match.title.query).toBe('java');
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.should[0].match.category.query).toBe('tech');
      expect(result.bool.should[0].match.category.boost).toBe(5);
    });

    it('merges with existing should clauses', () => {
      const existingBool = new Map<string, any>([
        ['must', [new Map([['match_all', new Map()]])]],
        ['should', [new Map([['match', new Map([['status', new Map([['query', 'featured']])]])]])]],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      const ctx = createCtx({ bq: 'category:food^10', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(2);
      expect(result.bool.should[0].match.status.query).toBe('featured');
      expect(result.bool.should[1].match.category.query).toBe('food');
      expect(result.bool.should[1].match.category.boost).toBe(10);
    });

    it('works alongside fq (must + filter + should all present)', () => {
      const existingBool = new Map<string, any>([
        ['must', [new Map([['match', new Map([['title', new Map([['query', 'cheese']])]])]])]],
        ['filter', [new Map([['match', new Map([['inStock', new Map([['query', 'true']])]])]])]],
      ]);
      const existingQuery = new Map([['bool', existingBool]]);
      const ctx = createCtx({ bq: 'category:food^10', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.filter).toHaveLength(1);
      expect(result.bool.should).toHaveLength(1);
    });
  });

  // --- no existing query ---

  describe('no existing query', () => {
    it('creates bool with match_all must and should when no existing query', () => {
      const ctx = createCtx({ bq: 'category:food', defType: 'edismax' });

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.must[0].match_all).toBeDefined();
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.minimum_should_match).toBeUndefined();
    });
  });

  // --- complex bq expressions ---

  describe('complex bq expressions', () => {
    it('handles boolean expression in bq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx(
        { bq: 'category:food AND inStock:true', defType: 'edismax' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.should[0].bool.must).toHaveLength(2);
    });

    it('handles match_all bq (*:*)', () => {
      const existingQuery = new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]);
      const ctx = createCtx({ bq: '*:*', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match_all).toBeDefined();
    });

    it('handles grouped bq expression', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx(
        { bq: '(category:food OR category:drink)^5', defType: 'edismax' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(1);
    });

    it('handles bare term bq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'wireless^5', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(1);
    });

    it('handles negation bq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: '-category:Books', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(1);
    });

    it('handles wildcard bq', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'title:wire*^5', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.should[0].wildcard).toBeDefined();
      expect(result.bool.should[0].wildcard.title).toBeDefined();
    });
  });

  // --- negative boost fail-fast ---

  describe('negative boost', () => {
    it('throws on negative boost in bq', () => {
      const ctx = createCtx({ bq: 'category:spam^-10', defType: 'edismax' }, new Map([['match_all', new Map()]]));
      expect(() => request.apply(ctx)).toThrow('[boost-query-bq] Negative boost in bq');
    });

    it('throws when any bq in a list has negative boost', () => {
      const ctx = createCtx(
        { bq: ['category:food^10', 'category:spam^-5'], defType: 'edismax' },
        new Map([['match_all', new Map()]]),
      );
      expect(() => request.apply(ctx)).toThrow('Negative boost in bq');
    });

    it('allows positive boost values', () => {
      const ctx = createCtx({ bq: 'category:food^10', defType: 'edismax' }, new Map([['match_all', new Map()]]));
      expect(() => request.apply(ctx)).not.toThrow();
    });

    it('does not false-positive on quoted values containing ^-', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'title:"some^-thing"', defType: 'edismax' }, existingQuery);
      expect(() => request.apply(ctx)).not.toThrow();
    });
  });

  // --- boost preservation ---

  describe('boost preservation', () => {
    it('preserves integer boost on field query', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'category:food^10', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match.category.boost).toBe(10);
    });

    it('preserves decimal boost on field query', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'category:food^2.5', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match.category.boost).toBe(2.5);
    });

    it('preserves boost on phrase query', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'title:"aged cheese"^3', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match_phrase.title.boost).toBe(3);
    });

    it('preserves boost on grouped expression', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: '(category:food OR category:drink)^5', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].bool.boost).toBe(5);
    });

    it('preserves different boosts across multiple bq params', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx(
        { bq: ['category:food^10', 'category:deli^0.5'], defType: 'edismax' },
        existingQuery,
      );

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match.category.boost).toBe(10);
      expect(result.bool.should[1].match.category.boost).toBe(0.5);
    });

    it('preserves zero boost', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'category:food^0', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match.category.boost).toBe(0);
    });

    it('preserves large boost', () => {
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = createCtx({ bq: 'category:food^99999', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.should[0].match.category.boost).toBe(99999);
    });
  });

  // --- non-bool existing query (else branch) ---

  describe('non-bool existing query', () => {
    it('wraps range query in must without nesting', () => {
      const existingQuery = new Map([['range', new Map([['price', new Map([['gte', '10'], ['lte', '100']])]])]]);
      const ctx = createCtx({ bq: 'category:food^5', defType: 'edismax' }, existingQuery);

      request.apply(ctx);

      const result = mapToObj(ctx.body.get('query'));
      expect(result.bool.must).toHaveLength(1);
      expect(result.bool.must[0].range.price.gte).toBe('10');
      expect(result.bool.should).toHaveLength(1);
      expect(result.bool.minimum_should_match).toBeUndefined();
    });
  });
});
