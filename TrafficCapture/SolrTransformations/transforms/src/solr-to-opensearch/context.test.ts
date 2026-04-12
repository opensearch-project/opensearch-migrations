/**
 * Unit tests for context.ts — parseParams URL decoding and endpoint detection.
 *
 * These tests run in Node (which has correct URLSearchParams), so they verify
 * the parseParams logic itself. The GraalVM polyfill + decoding is tested
 * separately in URLSearchParamsPolyfillTest.java.
 */
import { describe, it, expect } from 'vitest';
import { buildRequestContext } from './context';
import type { JavaMap } from './context';

/** Minimal JavaMap stub for testing. */
function mockMsg(uri: string): JavaMap {
  const data = new Map<string, any>([['URI', uri]]);
  return data as unknown as JavaMap;
}

describe('buildRequestContext', () => {
  describe('parseParams URL decoding', () => {
    it('decodes + as space in param values', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select?sort=price+asc'));
      expect(ctx.params.get('sort')).toBe('price asc');
    });

    it('decodes + as space in multiple params', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select?q=hello+world&sort=price+asc'));
      expect(ctx.params.get('q')).toBe('hello world');
      expect(ctx.params.get('sort')).toBe('price asc');
    });

    it('decodes %2B as literal +', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select?q=1%2B2'));
      expect(ctx.params.get('q')).toBe('1+2');
    });

    it('handles mixed + and %2B correctly', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select?q=%2B1+555+0100'));
      expect(ctx.params.get('q')).toBe('+1 555 0100');
    });

    it('decodes %20 as space', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select?sort=price%20asc'));
      expect(ctx.params.get('sort')).toBe('price asc');
    });

    it('returns empty params when no query string', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select'));
      expect(ctx.params.get('q')).toBeNull();
    });
  });

  describe('endpoint detection', () => {
    it('detects select endpoint', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/select?q=*:*'));
      expect(ctx.endpoint).toBe('select');
    });

    it('detects update endpoint', () => {
      const ctx = buildRequestContext(mockMsg('/solr/test/update'));
      expect(ctx.endpoint).toBe('update');
    });

    it('returns unknown for unrecognized paths', () => {
      const ctx = buildRequestContext(mockMsg('/other/path'));
      expect(ctx.endpoint).toBe('unknown');
    });
  });

  describe('collection extraction', () => {
    it('extracts collection from URI', () => {
      const ctx = buildRequestContext(mockMsg('/solr/mycore/select?q=*:*'));
      expect(ctx.collection).toBe('mycore');
    });

    it('returns undefined when no collection in URI', () => {
      const ctx = buildRequestContext(mockMsg('/other/path'));
      expect(ctx.collection).toBeUndefined();
    });
  });
});
