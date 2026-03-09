/**
 * Pagination micro-transform tests.
 *
 * Feature: solr-query-parser, Property 22: Pagination translation
 * **Validates: Requirements 12.8**
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { request as paginationTransform } from '../features/pagination';
import type { RequestContext } from '../context';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function mockRequestContext(params: URLSearchParams): RequestContext {
  const body: Map<string, any> = new Map();
  return {
    msg: new Map() as any,
    endpoint: 'select',
    collection: 'test',
    params,
    body: body as any,
  };
}

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('pagination micro-transform', () => {
  describe('unit tests', () => {
    it('has the correct name', () => {
      expect(paginationTransform.name).toBe('pagination');
    });

    it('does nothing when no start/rows params present', () => {
      const ctx = mockRequestContext(new URLSearchParams());
      paginationTransform.apply(ctx);
      expect(ctx.body.has('from')).toBe(false);
      expect(ctx.body.has('size')).toBe(false);
    });

    it('sets from when start is present', () => {
      const ctx = mockRequestContext(new URLSearchParams('start=10'));
      paginationTransform.apply(ctx);
      expect(ctx.body.get('from')).toBe(10);
    });

    it('sets size when rows is present', () => {
      const ctx = mockRequestContext(new URLSearchParams('rows=20'));
      paginationTransform.apply(ctx);
      expect(ctx.body.get('size')).toBe(20);
    });

    it('sets both from and size', () => {
      const ctx = mockRequestContext(new URLSearchParams('start=10&rows=20'));
      paginationTransform.apply(ctx);
      expect(ctx.body.get('from')).toBe(10);
      expect(ctx.body.get('size')).toBe(20);
    });

    it('handles zero values', () => {
      const ctx = mockRequestContext(new URLSearchParams('start=0&rows=0'));
      paginationTransform.apply(ctx);
      expect(ctx.body.get('from')).toBe(0);
      expect(ctx.body.get('size')).toBe(0);
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 22: Pagination translation
  // **Validates: Requirements 12.8**
  describe('Property 22: Pagination translation', () => {
    it('from equals start and size equals rows for non-negative integers', () => {
      fc.assert(
        fc.property(
          fc.nat({ max: 10000 }),
          fc.nat({ max: 10000 }),
          (start, rows) => {
            const ctx = mockRequestContext(
              new URLSearchParams(`start=${start}&rows=${rows}`),
            );
            paginationTransform.apply(ctx);

            expect(ctx.body.get('from')).toBe(start);
            expect(ctx.body.get('size')).toBe(rows);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
