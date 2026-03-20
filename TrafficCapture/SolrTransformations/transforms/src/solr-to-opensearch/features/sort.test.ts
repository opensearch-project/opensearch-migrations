import { describe, it, expect } from 'vitest';
import { request } from './sort';
import type { RequestContext, JavaMap } from '../context';

/**
 * Helper: build a RequestContext with the given params and an empty body.
 */
function buildCtx(params: string): RequestContext {
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams(params),
    body: new Map() as unknown as JavaMap,
  };
}

describe('sort MicroTransform', () => {
  describe('apply', () => {
    it('should not set sort when sort param is absent', () => {
      const ctx = buildCtx('');
      request.apply(ctx);
      expect(ctx.body.has('sort')).toBe(false);
    });

    it('should handle single field ascending', () => {
      const ctx = buildCtx('sort=price asc');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(1);
      expect(sort[0].get('price').get('order')).toBe('asc');
    });

    it('should handle single field descending', () => {
      const ctx = buildCtx('sort=price desc');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(1);
      expect(sort[0].get('price').get('order')).toBe('desc');
    });

    it('should default to asc when order is not specified', () => {
      const ctx = buildCtx('sort=price');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(1);
      expect(sort[0].get('price').get('order')).toBe('asc');
    });

    it('should handle multiple sort fields', () => {
      const ctx = buildCtx('sort=inStock desc, price asc');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(2);
      expect(sort[0].get('inStock').get('order')).toBe('desc');
      expect(sort[1].get('price').get('order')).toBe('asc');
    });

    it('should map score to _score', () => {
      const ctx = buildCtx('sort=score desc');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(1);
      expect(sort[0]).toBe('_score');
    });

    it('should handle score asc with explicit order', () => {
      const ctx = buildCtx('sort=score asc');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(1);
      expect(sort[0].get('_score').get('order')).toBe('asc');
    });

    it('should handle mixed score and field sort', () => {
      const ctx = buildCtx('sort=score desc, price asc');
      request.apply(ctx);
      const sort = ctx.body.get('sort');
      expect(sort).toHaveLength(2);
      expect(sort[0]).toBe('_score');
      expect(sort[1].get('price').get('order')).toBe('asc');
    });
  });
});
