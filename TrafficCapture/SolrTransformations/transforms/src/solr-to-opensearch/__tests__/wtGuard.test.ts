/**
 * Tests for the wt parameter guard in request.transform.ts.
 *
 * Feature: solr-query-parser, Property 24: Non-JSON wt passthrough
 * **Validates: Requirements 11.3**
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { transform } from '../request.transform';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Build a mock JavaMap message with a Solr select URI. */
function mockMessage(uri: string): Map<string, any> {
  const payload = new Map<string, any>();
  const msg = new Map<string, any>();
  msg.set('URI', uri);
  msg.set('payload', payload);
  return msg;
}

// ─── Generators ───────────────────────────────────────────────────────────────

/** Generate non-JSON wt values (xml, csv, python, ruby, php, javabin, etc.) */
const arbNonJsonWt = () =>
  fc.constantFrom('xml', 'csv', 'python', 'ruby', 'php', 'javabin', 'phps');

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

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('wt parameter guard', () => {
  describe('unit tests', () => {
    it('passes through untransformed when wt=xml', () => {
      const msg = mockMessage('/solr/mycore/select?q=*:*&wt=xml');
      const result = transform(msg);
      // Should return the original message without inlinedJsonBody
      const payload = result.get('payload');
      expect(payload.has('inlinedJsonBody')).toBe(false);
    });

    it('passes through untransformed when wt=csv', () => {
      const msg = mockMessage('/solr/mycore/select?q=*:*&wt=csv');
      const result = transform(msg);
      const payload = result.get('payload');
      expect(payload.has('inlinedJsonBody')).toBe(false);
    });

    it('transforms normally when wt=json', () => {
      const msg = mockMessage('/solr/mycore/select?q=*:*&wt=json');
      const result = transform(msg);
      const payload = result.get('payload');
      expect(payload.has('inlinedJsonBody')).toBe(true);
      const body = payload.get('inlinedJsonBody');
      expect(body).toBeInstanceOf(Map);
      expect(body.has('query')).toBe(true);
    });

    it('transforms normally when wt is absent (defaults to json)', () => {
      const msg = mockMessage('/solr/mycore/select?q=*:*');
      const result = transform(msg);
      const payload = result.get('payload');
      expect(payload.has('inlinedJsonBody')).toBe(true);
      const body = payload.get('inlinedJsonBody');
      expect(body).toBeInstanceOf(Map);
      expect(body.has('query')).toBe(true);
    });

    it('returns the exact same message reference for non-JSON wt', () => {
      const msg = mockMessage('/solr/mycore/select?q=title:java&wt=xml');
      const result = transform(msg);
      expect(result).toBe(msg);
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 24: Non-JSON wt passthrough
  // **Validates: Requirements 11.3**
  describe('Property 24: Non-JSON wt passthrough', () => {
    it('non-JSON wt values pass through untransformed', () => {
      fc.assert(
        fc.property(
          arbNonJsonWt(),
          arbFieldName(),
          arbValue(),
          (wt, field, value) => {
            const msg = mockMessage(`/solr/mycore/select?q=${field}:${value}&wt=${wt}`);
            const result = transform(msg);
            // Must return the original message reference
            expect(result).toBe(msg);
            // Payload must not have inlinedJsonBody (no transformation occurred)
            const payload = result.get('payload');
            expect(payload.has('inlinedJsonBody')).toBe(false);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('wt=json still gets transformed', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          (field, value) => {
            const msg = mockMessage(`/solr/mycore/select?q=${field}:${value}&wt=json`);
            const result = transform(msg);
            const payload = result.get('payload');
            expect(payload.has('inlinedJsonBody')).toBe(true);
            const body = payload.get('inlinedJsonBody');
            expect(body).toBeInstanceOf(Map);
            expect(body.has('query')).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('absent wt (defaults to json) still gets transformed', () => {
      fc.assert(
        fc.property(
          arbFieldName(),
          arbValue(),
          (field, value) => {
            const msg = mockMessage(`/solr/mycore/select?q=${field}:${value}`);
            const result = transform(msg);
            const payload = result.get('payload');
            expect(payload.has('inlinedJsonBody')).toBe(true);
            const body = payload.get('inlinedJsonBody');
            expect(body).toBeInstanceOf(Map);
            expect(body.has('query')).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
