import { describe, it, expect } from 'vitest';
import { request } from './delete-doc';
import type { RequestContext, JavaMap } from '../context';

function buildCtx(id: any, collection: string | undefined = 'mycore'): RequestContext {
  const body = new Map<string, any>([['id', id]]);
  const msg = new Map<string, any>([
    ['URI', '/solr/mycore/update'],
    ['method', 'POST'],
  ]) as unknown as JavaMap;
  return {
    msg,
    endpoint: 'update',
    collection,
    params: new URLSearchParams(),
    body: body as unknown as JavaMap,
  };
}

describe('delete-doc', () => {
  // --- happy path ---

  it('rewrites URI to DELETE /_doc/{id}', () => {
    const ctx = buildCtx('1');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
    expect(ctx.msg.get('method')).toBe('DELETE');
  });

  it('encodes special characters in id', () => {
    const ctx = buildCtx('doc/with spaces');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/doc%2Fwith%20spaces');
  });

  it('handles numeric id', () => {
    const ctx = buildCtx(42);
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/42');
  });

  it('handles id with dashes and underscores', () => {
    const ctx = buildCtx('doc-with-dashes_and_underscores');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/doc-with-dashes_and_underscores');
  });

  it('trims whitespace from id', () => {
    const ctx = buildCtx('  doc1  ');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/doc1');
  });

  it('translates commit=true to refresh=true', () => {
    const body = new Map<string, any>([['id', '1']]);
    const msg = new Map<string, any>([['URI', '/solr/mycore/update'], ['method', 'POST']]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams('commit=true'), body: body as unknown as JavaMap,
    };
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1?refresh=true');
  });

  it('does not add refresh when commit is absent', () => {
    const ctx = buildCtx('1');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  // --- error cases ---

  it('throws on empty id', () => {
    const ctx = buildCtx('');
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws on null id', () => {
    const ctx = buildCtx(null);
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws on whitespace-only id', () => {
    const ctx = buildCtx('   ');
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws on undefined id', () => {
    const body = new Map<string, any>();
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update'],
      ['method', 'POST'],
    ]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws when collection is undefined', () => {
    const body = new Map<string, any>([['id', '1']]);
    const msg = new Map<string, any>([['URI', '/update'], ['method', 'POST']]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: undefined,
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    expect(() => request.apply(ctx)).toThrow('collection could not be determined');
  });
});
