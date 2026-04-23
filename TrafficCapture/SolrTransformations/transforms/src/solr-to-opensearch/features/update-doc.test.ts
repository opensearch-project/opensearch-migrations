import { describe, it, expect } from 'vitest';
import { request } from './update-doc';
import type { RequestContext, JavaMap } from '../context';

function buildCtx(
  uri: string,
  body: Map<string, any> = new Map(),
): RequestContext {
  const headers = new Map<string, string>();
  const msg = new Map<string, any>([
    ['URI', uri],
    ['method', 'POST'],
    ['headers', headers],
  ]) as unknown as JavaMap;

  const q = uri.indexOf('?');
  const params = new URLSearchParams(q >= 0 ? uri.slice(q + 1) : '');

  return {
    msg,
    endpoint: 'update',
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    params,
    body: body as unknown as JavaMap,
  };
}

describe('update-doc', () => {
  // --- happy path ---

  it('rewrites URI to /_doc/{id} with PUT method', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
    expect(ctx.msg.get('method')).toBe('PUT');
  });

  it('encodes special characters in id', () => {
    const body = new Map([['id', 'doc/with spaces'], ['title', 'test']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/doc%2Fwith%20spaces');
  });

  it('handles numeric id', () => {
    const body = new Map<string, any>([['id', 42], ['title', 'test']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/42');
  });

  // --- commit handling ---

  it('translates commit=true to refresh=true', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs?commit=true', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1?refresh=true');
  });

  it('does not add refresh when commit is absent', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  it('does not add refresh when commit=false', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs?commit=false', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  // --- commit: commitWithin → refresh=true ---

  it('translates commitWithin to refresh=true', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs?commitWithin=10000', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1?refresh=true');
  });

  it('translates commitWithin=0 to refresh=true', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs?commitWithin=0', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1?refresh=true');
  });

  // --- commit edge cases ---

  it('does not add refresh for commit=yes (only commit=true triggers it)', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs?commit=yes', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  it('does not add refresh for commit=1', () => {
    const body = new Map([['id', '1'], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs?commit=1', body);

    request.apply(ctx);

    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  // --- fail-fast: body validation ---

  it('throws on empty body', () => {
    const ctx = buildCtx('/solr/mycore/update/json/docs', new Map());

    expect(() => request.apply(ctx)).toThrow('Request body is empty');
  });

  it('throws on missing id field', () => {
    const body = new Map([['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    expect(() => request.apply(ctx)).toThrow('must have an "id" field');
  });

  it('throws on empty string id', () => {
    const body = new Map([['id', ''], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    expect(() => request.apply(ctx)).toThrow('must have an "id" field');
  });

  it('throws on null id', () => {
    const body = new Map<string, any>([['id', null], ['title', 'hello']]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', body);

    expect(() => request.apply(ctx)).toThrow('must have an "id" field');
  });

  it('throws on array body', () => {
    // Simulate GraalVM ArrayList: has numeric keys + length
    const arrayLike = new Map<string, any>([
      ['0', { id: '1' }],
      ['1', { id: '2' }],
      ['length', 2],
    ]);
    const ctx = buildCtx('/solr/mycore/update/json/docs', arrayLike);

    expect(() => request.apply(ctx)).toThrow('Array/bulk updates not supported');
  });
});
