/**
 * Integration tests for the query-q micro-transform.
 *
 * Feature: solr-query-parser, Property 25: Micro-transform sets query on body
 * **Validates: Requirements 7.4**
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { request as queryQTransform } from '../features/query-q';
import type { RequestContext } from '../context';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Build a minimal mock RequestContext with the given URL params. */
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

/** Generate valid simple Solr queries. */
const arbSimpleSolrQuery = () =>
  fc.oneof(
    fc.tuple(arbFieldName(), arbValue()).map(([f, v]) => `${f}:${v}`),
    fc.constant('*:*'),
    arbValue(),
    fc.stringMatching(/^[a-zA-Z0-9 ]{1,15}$/).filter((s) => s.trim().length > 0).map((s) => `"${s}"`),
  );

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('query-q micro-transform', () => {
  describe('unit tests', () => {
    it('has the correct name', () => {
      expect(queryQTransform.name).toBe('query-q');
    });

    it('sets query on body for *:*', () => {
      const ctx = mockRequestContext(new URLSearchParams('q=*:*'));
      queryQTransform.apply(ctx);
      expect(ctx.body.get('query')).toBeInstanceOf(Map);
    });

    it('sets query on body for field:value', () => {
      const ctx = mockRequestContext(new URLSearchParams('q=title:java'));
      queryQTransform.apply(ctx);
      const query = ctx.body.get('query');
      expect(query).toBeInstanceOf(Map);
      expect(query.get('term')).toBeInstanceOf(Map);
    });

    it('defaults to *:* when q is absent', () => {
      const ctx = mockRequestContext(new URLSearchParams());
      queryQTransform.apply(ctx);
      const query = ctx.body.get('query');
      expect(query).toBeInstanceOf(Map);
      expect(query.has('match_all')).toBe(true);
    });

    it('passes defType, qf, pf, df to translator', () => {
      const params = new URLSearchParams('q=java&defType=edismax&qf=title^2 content&df=text');
      const ctx = mockRequestContext(params);
      queryQTransform.apply(ctx);
      expect(ctx.body.get('query')).toBeInstanceOf(Map);
    });

    it('attaches _solr_warnings when warnings are present', () => {
      const params = new URLSearchParams('q=test&defType=dismax');
      const ctx = mockRequestContext(params);
      queryQTransform.apply(ctx);
      expect(ctx.body.get('_solr_warnings')).toBeDefined();
      expect(ctx.body.get('_solr_warnings').length).toBeGreaterThan(0);
    });

    it('does not attach _solr_warnings when no warnings', () => {
      const ctx = mockRequestContext(new URLSearchParams('q=title:java'));
      queryQTransform.apply(ctx);
      expect(ctx.body.has('_solr_warnings')).toBe(false);
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 25: Micro-transform sets query on body
  // **Validates: Requirements 7.4**
  describe('Property 25: Micro-transform sets query on body', () => {
    it('ctx.body.get("query") is always a Map after applying query-q', () => {
      fc.assert(
        fc.property(
          arbSimpleSolrQuery(),
          (q) => {
            const ctx = mockRequestContext(new URLSearchParams(`q=${encodeURIComponent(q)}`));
            queryQTransform.apply(ctx);
            const query = ctx.body.get('query');
            expect(query).toBeInstanceOf(Map);
            expect(query).not.toBeNull();
            expect(query).not.toBeUndefined();
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
