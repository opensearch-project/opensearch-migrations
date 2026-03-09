/**
 * Filter query (fq) micro-transform tests.
 *
 * Feature: solr-query-parser, Property 20: Filter query translation
 * **Validates: Requirements 12.3**
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { request as filterFqTransform } from '../features/filter-fq';
import type { RequestContext } from '../context';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Build a minimal mock RequestContext with the given URL params and optional existing query. */
function mockRequestContext(params: URLSearchParams, existingQuery?: Map<string, any>): RequestContext {
  const body: Map<string, any> = new Map();
  if (existingQuery) {
    body.set('query', existingQuery);
  }
  return {
    msg: new Map() as any,
    endpoint: 'select',
    collection: 'test',
    params,
    body: body as any,
  };
}

/** Recursively check that no Map in the structure contains a 'boost' key. */
function hasNoBoostKeys(value: any): boolean {
  if (value instanceof Map) {
    if (value.has('boost')) return false;
    for (const v of value.values()) {
      if (!hasNoBoostKeys(v)) return false;
    }
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      if (!hasNoBoostKeys(item)) return false;
    }
  }
  return true;
}

// ─── Generators ───────────────────────────────────────────────────────────────

const arbFieldName = () =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

const arbValue = () =>
  fc.stringMatching(/^[a-z][a-z0-9]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Generate valid fq values (field:value format). */
const arbFqValue = () =>
  fc.tuple(arbFieldName(), arbValue()).map(([f, v]) => `${f}:${v}`);

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('filter-fq micro-transform', () => {
  describe('unit tests', () => {
    it('has the correct name', () => {
      expect(filterFqTransform.name).toBe('filter-fq');
    });

    it('does nothing when no fq params present', () => {
      const ctx = mockRequestContext(new URLSearchParams());
      filterFqTransform.apply(ctx);
      expect(ctx.body.has('query')).toBe(false);
    });

    it('wraps single fq in bool.filter', () => {
      const params = new URLSearchParams('fq=status:active');
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = mockRequestContext(params, existingQuery);
      filterFqTransform.apply(ctx);

      const query = ctx.body.get('query');
      expect(query).toBeInstanceOf(Map);
      expect(query.has('bool')).toBe(true);

      const boolMap = query.get('bool');
      expect(boolMap.get('filter')).toHaveLength(1);
      expect(boolMap.get('must')).toHaveLength(1);
    });

    it('handles multiple fq values', () => {
      const params = new URLSearchParams();
      params.append('fq', 'status:active');
      params.append('fq', 'type:article');
      const existingQuery = new Map([['match_all', new Map()]]);
      const ctx = mockRequestContext(params, existingQuery);
      filterFqTransform.apply(ctx);

      const boolMap = ctx.body.get('query').get('bool');
      expect(boolMap.get('filter')).toHaveLength(2);
    });

    it('works without existing query', () => {
      const params = new URLSearchParams('fq=status:active');
      const ctx = mockRequestContext(params);
      filterFqTransform.apply(ctx);

      const query = ctx.body.get('query');
      expect(query).toBeInstanceOf(Map);
      const boolMap = query.get('bool');
      expect(boolMap.get('filter')).toHaveLength(1);
      expect(boolMap.has('must')).toBe(false);
    });

    it('strips boost keys from filter clauses', () => {
      const params = new URLSearchParams('fq=title:java^2');
      const ctx = mockRequestContext(params);
      filterFqTransform.apply(ctx);

      const boolMap = ctx.body.get('query').get('bool');
      const filterClauses = boolMap.get('filter');
      for (const clause of filterClauses) {
        expect(hasNoBoostKeys(clause)).toBe(true);
      }
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 20: Filter query translation
  // **Validates: Requirements 12.3**
  describe('Property 20: Filter query translation', () => {
    it('fq values produce bool.filter clauses without scoring keys', () => {
      fc.assert(
        fc.property(
          fc.array(arbFqValue(), { minLength: 1, maxLength: 5 }),
          (fqValues) => {
            const params = new URLSearchParams();
            for (const fq of fqValues) {
              params.append('fq', fq);
            }
            const existingQuery = new Map([['match_all', new Map()]]);
            const ctx = mockRequestContext(params, existingQuery);
            filterFqTransform.apply(ctx);

            // Assert bool.filter structure exists
            const query = ctx.body.get('query');
            expect(query).toBeInstanceOf(Map);
            expect(query.has('bool')).toBe(true);

            const boolMap = query.get('bool');
            const filterClauses = boolMap.get('filter');
            expect(filterClauses).toHaveLength(fqValues.length);

            // Assert no scoring keys (boost) in filter clauses
            for (const clause of filterClauses) {
              expect(hasNoBoostKeys(clause)).toBe(true);
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
