import { describe, it, expect } from 'vitest';
import { request } from './field-list';
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

describe('field-list MicroTransform', () => {
  describe('apply', () => {
    it('should not set _source when fl is absent', () => {
      const ctx = buildCtx('');
      request.apply(ctx);
      expect(ctx.body.has('_source')).toBe(false);
    });

    it('should not set _source when fl=*', () => {
      const ctx = buildCtx('fl=*');
      request.apply(ctx);
      expect(ctx.body.has('_source')).toBe(false);
    });

    it('should not set _source when fl is only whitespace', () => {
      const ctx = buildCtx('fl=  ');
      request.apply(ctx);
      expect(ctx.body.has('_source')).toBe(false);
    });

    it('should set _source for comma-separated fields', () => {
      const ctx = buildCtx('fl=id,name,price');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'name', 'price']);
    });

    it('should set _source for space-separated fields', () => {
      const ctx = buildCtx('fl=id name price');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'name', 'price']);
    });

    it('should set _source for mixed comma and space separated fields', () => {
      const ctx = buildCtx('fl=id,name price');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'name', 'price']);
    });

    it('should filter out score pseudo-field', () => {
      const ctx = buildCtx('fl=id,score,name');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'name']);
    });

    it('should filter out * wildcard when mixed with other fields', () => {
      const ctx = buildCtx('fl=*,score');
      request.apply(ctx);
      expect(ctx.body.has('_source')).toBe(false);
    });

    it('should pass through glob patterns like na*', () => {
      const ctx = buildCtx('fl=id,na*,price');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'na*', 'price']);
    });

    it('should handle glob pattern na*e', () => {
      const ctx = buildCtx('fl=id na*e price');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'na*e', 'price']);
    });

    it('should filter out document transformers like [explain]', () => {
      const ctx = buildCtx('fl=id,[explain],name');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'name']);
    });

    it('should handle single field', () => {
      const ctx = buildCtx('fl=id');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id']);
    });

    it('should handle fields with extra whitespace', () => {
      const ctx = buildCtx('fl=  id  ,  name  ');
      request.apply(ctx);
      expect(ctx.body.get('_source')).toEqual(['id', 'name']);
    });

    it('should return null when all fields are pseudo-fields', () => {
      const ctx = buildCtx('fl=score');
      request.apply(ctx);
      expect(ctx.body.has('_source')).toBe(false);
    });
  });
});
