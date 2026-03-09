/**
 * Translator tests — unit + property-based tests for translateQ.
 *
 * Property tests use fast-check to validate correctness properties
 * from the design document.
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { translateQ } from '../translator/translateQ';
import type { TranslateOptions } from '../translator/translateQ';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Recursively compare two Map-based DSL structures for deep equality. */
function deepEqualMaps(a: any, b: any): boolean {
  if (a === b) return true;
  if (a instanceof Map && b instanceof Map) {
    if (a.size !== b.size) return false;
    const aKeys = [...a.keys()];
    const bKeys = [...b.keys()];
    if (aKeys.length !== bKeys.length) return false;
    for (let i = 0; i < aKeys.length; i++) {
      if (aKeys[i] !== bKeys[i]) return false;
      if (!deepEqualMaps(a.get(aKeys[i]), b.get(bKeys[i]))) return false;
    }
    return true;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      if (!deepEqualMaps(a[i], b[i])) return false;
    }
    return true;
  }
  return false;
}

/** Check that a DSL Map is a query_string passthrough containing the raw query. */
function assertQueryStringPassthrough(dsl: Map<string, any>, q: string): void {
  expect(dsl).toBeInstanceOf(Map);
  expect(dsl.has('query_string')).toBe(true);
  const inner = dsl.get('query_string');
  expect(inner).toBeInstanceOf(Map);
  expect(inner.get('query')).toBe(q);
}

// ─── Generators ───────────────────────────────────────────────────────────────

/** Generate unsupported defType values (not lucene, edismax, or undefined). */
const arbUnsupportedDefType = () =>
  fc.stringMatching(/^[a-z]{3,10}$/).filter(
    (s) => s !== 'lucene' && s !== 'edismax',
  );

/** Generate alphanumeric field names. */
const arbFieldName = () =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Generate alphanumeric values. */
const arbValue = () =>
  fc.stringMatching(/^[a-z][a-z0-9]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Generate valid simple Solr queries (field:value, bare value, phrases, *:*). */
const arbSimpleSolrQuery = () =>
  fc.oneof(
    // field:value
    fc.tuple(arbFieldName(), arbValue()).map(([f, v]) => `${f}:${v}`),
    // bare value
    arbValue(),
    // phrase
    fc.stringMatching(/^[a-zA-Z0-9 ]{1,15}$/).filter((s) => s.trim().length > 0).map((s) => `"${s}"`),
    // match all
    fc.constant('*:*'),
  );

/** Generate valid Solr queries with boolean operators. */
const arbSolrQuery = () =>
  fc.oneof(
    arbSimpleSolrQuery(),
    fc.tuple(arbSimpleSolrQuery(), arbSimpleSolrQuery()).map(([a, b]) => `${a} AND ${b}`),
    fc.tuple(arbSimpleSolrQuery(), arbSimpleSolrQuery()).map(([a, b]) => `${a} OR ${b}`),
    fc.tuple(arbFieldName(), arbValue(), arbValue()).map(
      ([f, lo, hi]) => `${f}:[${lo} TO ${hi}]`,
    ),
  );

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('Translator', () => {
  describe('unit tests', () => {
    it('translates *:* to match_all', () => {
      const result = translateQ({ q: '*:*' });
      expect(result.dsl.has('match_all')).toBe(true);
      expect(result.warnings).toHaveLength(0);
    });

    it('translates field:value to term query', () => {
      const result = translateQ({ q: 'title:java' });
      expect(result.dsl.get('term')).toBeInstanceOf(Map);
      expect(result.dsl.get('term').get('title')).toBe('java');
    });

    it('translates phrase to match_phrase with default field', () => {
      const result = translateQ({ q: '"hello world"', df: 'content' });
      expect(result.dsl.get('match_phrase').get('content')).toBe('hello world');
    });

    it('translates AND query to bool.must', () => {
      const result = translateQ({ q: 'title:java AND author:smith' });
      const boolMap = result.dsl.get('bool');
      expect(boolMap).toBeInstanceOf(Map);
      expect(boolMap.get('must')).toHaveLength(2);
    });

    it('falls back to query_string for unsupported defType', () => {
      const q = 'title:java';
      const result = translateQ({ q, defType: 'dismax' });
      assertQueryStringPassthrough(result.dsl, q);
      expect(result.warnings.length).toBeGreaterThan(0);
    });

    it('falls back to query_string on lexer error (unterminated quote)', () => {
      const q = '"unterminated';
      const result = translateQ({ q });
      assertQueryStringPassthrough(result.dsl, q);
      expect(result.warnings.length).toBeGreaterThan(0);
    });

    it('handles edismax defType with qf', () => {
      const result = translateQ({
        q: 'java',
        defType: 'edismax',
        qf: 'title^2 content',
      });
      // Should produce a bool with should clauses for the qf fields
      expect(result.dsl).toBeInstanceOf(Map);
      expect(result.warnings).toHaveLength(0);
    });

    it('uses _text_ as default field when df not provided', () => {
      const result = translateQ({ q: 'java' });
      expect(result.dsl.get('term').get('_text_')).toBe('java');
    });

    it('supports strict mode — falls back on first error', () => {
      const q = '"unterminated';
      const result = translateQ({ q }, { mode: 'strict' });
      assertQueryStringPassthrough(result.dsl, q);
      expect(result.warnings.length).toBe(1);
    });

    it('supports best-effort mode by default', () => {
      const q = '"unterminated';
      const result = translateQ({ q });
      assertQueryStringPassthrough(result.dsl, q);
      expect(result.warnings.length).toBeGreaterThan(0);
    });

    it('returns warnings with construct, position, and message for unsupported defType', () => {
      const result = translateQ({ q: 'test', defType: 'spatial' });
      expect(result.warnings.length).toBeGreaterThan(0);
      const w = result.warnings[0];
      expect(w.construct).toBeTruthy();
      expect(typeof w.position).toBe('number');
      expect(w.message).toBeTruthy();
    });
  });


  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 17: Translator fallback on error
  // **Validates: Requirements 6.4, 6.6, 9.3**
  describe('Property 17: Translator fallback on error', () => {
    it('unsupported defType produces query_string passthrough', () => {
      fc.assert(
        fc.property(
          arbUnsupportedDefType(),
          arbSimpleSolrQuery(),
          (defType, q) => {
            const result = translateQ({ q, defType });
            assertQueryStringPassthrough(result.dsl, q);
            expect(result.warnings.length).toBeGreaterThan(0);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('lexer errors produce query_string passthrough', () => {
      fc.assert(
        fc.property(
          // Generate queries with unterminated quotes
          arbValue().map((v) => `"${v}`),
          (q) => {
            const result = translateQ({ q });
            assertQueryStringPassthrough(result.dsl, q);
            expect(result.warnings.length).toBeGreaterThan(0);
            // Each warning should have construct, position, message
            for (const w of result.warnings) {
              expect(w.construct).toBeTruthy();
              expect(typeof w.position).toBe('number');
              expect(w.message).toBeTruthy();
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 18: Deterministic output
  // **Validates: Requirements 14.1**
  describe('Property 18: Deterministic output', () => {
    it('invoking translator N times produces identical outputs', () => {
      fc.assert(
        fc.property(
          arbSolrQuery(),
          fc.constantFrom(undefined, 'lucene', 'edismax') as fc.Arbitrary<string | undefined>,
          fc.constantFrom(undefined, 'title^2 content') as fc.Arbitrary<string | undefined>,
          fc.constantFrom(undefined, 'content', '_text_') as fc.Arbitrary<string | undefined>,
          (q, defType, qf, df) => {
            const opts: TranslateOptions = { q, defType, qf, df };
            const N = 5;
            const results: Map<string, any>[] = [];

            for (let i = 0; i < N; i++) {
              results.push(translateQ(opts).dsl);
            }

            // All N results must be deeply equal
            for (let i = 1; i < N; i++) {
              expect(deepEqualMaps(results[0], results[i])).toBe(true);
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 23: Warning structure completeness
  // **Validates: Requirements 15.1, 15.3, 15.4, 15.5**
  describe('Property 23: Warning structure completeness', () => {
    it('unsupported defType warnings have non-empty construct, numeric position, non-empty message', () => {
      fc.assert(
        fc.property(
          arbUnsupportedDefType(),
          arbSimpleSolrQuery(),
          (defType, q) => {
            const result = translateQ({ q, defType });
            expect(result.warnings.length).toBeGreaterThan(0);

            for (const w of result.warnings) {
              // Non-empty construct string
              expect(typeof w.construct).toBe('string');
              expect(w.construct.length).toBeGreaterThan(0);

              // Numeric position (when applicable)
              expect(typeof w.position).toBe('number');

              // Non-empty message string
              expect(typeof w.message).toBe('string');
              expect(w.message.length).toBeGreaterThan(0);
            }
          },
        ),
        { numRuns: 100 },
      );
    });

    it('lexer error warnings have complete structure', () => {
      fc.assert(
        fc.property(
          arbValue().map((v) => `"${v}`),
          (q) => {
            const result = translateQ({ q });
            expect(result.warnings.length).toBeGreaterThan(0);

            for (const w of result.warnings) {
              expect(typeof w.construct).toBe('string');
              expect(w.construct.length).toBeGreaterThan(0);
              expect(typeof w.position).toBe('number');
              expect(typeof w.message).toBe('string');
              expect(w.message.length).toBeGreaterThan(0);
            }
          },
        ),
        { numRuns: 100 },
      );
    });

    it('supported portions still translate in best-effort mode with unsupported defType', () => {
      fc.assert(
        fc.property(
          arbUnsupportedDefType(),
          arbSimpleSolrQuery(),
          (defType, q) => {
            const result = translateQ({ q, defType });
            // Even with warnings, the DSL should be a valid Map (query_string passthrough)
            expect(result.dsl).toBeInstanceOf(Map);
            expect(result.dsl.size).toBeGreaterThan(0);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
