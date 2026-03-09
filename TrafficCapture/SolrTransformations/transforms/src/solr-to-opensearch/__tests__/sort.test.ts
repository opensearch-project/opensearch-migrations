/**
 * Sort micro-transform tests.
 *
 * Feature: solr-query-parser, Property 21: Sort translation
 * **Validates: Requirements 12.7**
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { request as sortTransform } from '../features/sort';
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

// ─── Generators ───────────────────────────────────────────────────────────────

const arbFieldName = () =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

const arbDirection = () => fc.constantFrom('asc', 'desc');

/** Generate a single sort pair like "price asc" or "score desc". */
const arbSortPair = () =>
  fc.tuple(
    fc.oneof(arbFieldName(), fc.constant('score')),
    arbDirection(),
  ).map(([field, dir]) => `${field} ${dir}`);

/** Generate a full sort string with 1-4 comma-separated pairs. */
const arbSortString = () =>
  fc.array(arbSortPair(), { minLength: 1, maxLength: 4 })
    .map((pairs) => pairs.join(', '));

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('sort micro-transform', () => {
  describe('unit tests', () => {
    it('has the correct name', () => {
      expect(sortTransform.name).toBe('sort');
    });

    it('does nothing when no sort param present', () => {
      const ctx = mockRequestContext(new URLSearchParams());
      sortTransform.apply(ctx);
      expect(ctx.body.has('sort')).toBe(false);
    });

    it('parses single sort field', () => {
      const ctx = mockRequestContext(new URLSearchParams('sort=price asc'));
      sortTransform.apply(ctx);
      const sortArray = ctx.body.get('sort');
      expect(sortArray).toHaveLength(1);
      expect(sortArray[0].get('price').get('order')).toBe('asc');
    });

    it('maps score to _score', () => {
      const ctx = mockRequestContext(new URLSearchParams('sort=score desc'));
      sortTransform.apply(ctx);
      const sortArray = ctx.body.get('sort');
      expect(sortArray).toHaveLength(1);
      expect(sortArray[0].has('_score')).toBe(true);
      expect(sortArray[0].get('_score').get('order')).toBe('desc');
    });

    it('handles multiple sort fields', () => {
      const ctx = mockRequestContext(new URLSearchParams('sort=price asc, score desc'));
      sortTransform.apply(ctx);
      const sortArray = ctx.body.get('sort');
      expect(sortArray).toHaveLength(2);
      expect(sortArray[0].get('price').get('order')).toBe('asc');
      expect(sortArray[1].get('_score').get('order')).toBe('desc');
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 21: Sort translation
  // **Validates: Requirements 12.7**
  describe('Property 21: Sort translation', () => {
    it('sort strings produce correct field names and directions', () => {
      fc.assert(
        fc.property(
          arbSortString(),
          (sortStr) => {
            const ctx = mockRequestContext(new URLSearchParams(`sort=${sortStr}`));
            sortTransform.apply(ctx);

            const sortArray = ctx.body.get('sort');
            expect(Array.isArray(sortArray)).toBe(true);
            expect(sortArray.length).toBeGreaterThan(0);

            // Parse the input to verify against output
            const pairs = sortStr.split(',').map((p: string) => p.trim()).filter(Boolean);

            expect(sortArray).toHaveLength(pairs.length);

            for (let i = 0; i < pairs.length; i++) {
              const parts = pairs[i].split(/\s+/);
              const expectedField = parts[0] === 'score' ? '_score' : parts[0];
              const expectedDir = parts[1]?.toLowerCase() || 'asc';

              const sortEntry = sortArray[i];
              expect(sortEntry).toBeInstanceOf(Map);
              expect(sortEntry.has(expectedField)).toBe(true);
              expect(sortEntry.get(expectedField).get('order')).toBe(expectedDir);
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
