import { describe, it, expect } from 'vitest';
import { request } from './json-request';
import type { RequestContext, JavaMap } from '../context';

function buildCtx(
  body: Record<string, any> = {},
  urlParams: Record<string, string> = {},
): RequestContext {
  const bodyMap = new Map(Object.entries(body)) as unknown as JavaMap;
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'test',
    params: new URLSearchParams(urlParams),
    body: bodyMap,
  };
}

describe('json-request', () => {
  // --- match guard ---

  it('matches when body has "query" key', () => {
    const ctx = buildCtx({ query: '*:*' });
    expect(request.match!(ctx)).toBe(true);
  });

  it('does not match empty body', () => {
    const ctx = buildCtx({});
    expect(request.match!(ctx)).toBe(false);
  });

  it('does not match body without "query" key', () => {
    const ctx = buildCtx({ limit: 10 });
    expect(request.match!(ctx)).toBe(false);
  });

  // --- key mapping ---

  it('maps query → q', () => {
    const ctx = buildCtx({ query: 'title:java' });
    request.apply(ctx);
    expect(ctx.params.get('q')).toBe('title:java');
  });

  it('maps limit → rows', () => {
    const ctx = buildCtx({ query: '*:*', limit: 10 });
    request.apply(ctx);
    expect(ctx.params.get('rows')).toBe('10');
  });

  it('maps offset → start', () => {
    const ctx = buildCtx({ query: '*:*', offset: 20 });
    request.apply(ctx);
    expect(ctx.params.get('start')).toBe('20');
  });

  it('maps sort → sort', () => {
    const ctx = buildCtx({ query: '*:*', sort: 'price asc' });
    request.apply(ctx);
    expect(ctx.params.get('sort')).toBe('price asc');
  });

  it('maps filter → fq', () => {
    const ctx = buildCtx({ query: '*:*', filter: 'category:electronics' });
    request.apply(ctx);
    expect(ctx.params.get('fq')).toBe('category:electronics');
  });

  it('maps fields → fl', () => {
    const ctx = buildCtx({ query: '*:*', fields: 'id,title,price' });
    request.apply(ctx);
    expect(ctx.params.get('fl')).toBe('id,title,price');
  });

  it('maps fields array to comma-separated fl', () => {
    const ctx = buildCtx({ query: '*:*', fields: ['id', 'title', 'price'] });
    request.apply(ctx);
    expect(ctx.params.get('fl')).toBe('id,title,price');
  });

  it('maps all keys together', () => {
    const ctx = buildCtx({
      query: 'title:java',
      limit: 5,
      offset: 10,
      sort: 'price desc',
      fields: ['id', 'title'],
    });
    request.apply(ctx);
    expect(ctx.params.get('q')).toBe('title:java');
    expect(ctx.params.get('rows')).toBe('5');
    expect(ctx.params.get('start')).toBe('10');
    expect(ctx.params.get('sort')).toBe('price desc');
    expect(ctx.params.get('fl')).toBe('id,title');
  });

  // --- JSON body takes precedence over URL params ---

  it('overwrites existing URL param q with JSON body query', () => {
    const ctx = buildCtx({ query: 'from-body' }, { q: 'from-url' });
    request.apply(ctx);
    expect(ctx.params.get('q')).toBe('from-body');
  });

  it('overwrites existing URL param rows with JSON body limit', () => {
    const ctx = buildCtx({ query: '*:*', limit: 99 }, { rows: '5' });
    request.apply(ctx);
    expect(ctx.params.get('rows')).toBe('99');
  });

  // --- params object ---

  it('merges params object into ctx.params', () => {
    const ctx = buildCtx({
      query: '*:*',
      params: new Map([['df', 'title'], ['wt', 'json']]),
    });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('title');
    expect(ctx.params.get('wt')).toBe('json');
  });

  it('params object does not overwrite existing URL params', () => {
    const ctx = buildCtx(
      { query: '*:*', params: new Map([['wt', 'xml']]) },
      { wt: 'json' },
    );
    request.apply(ctx);
    expect(ctx.params.get('wt')).toBe('json');
  });

  // --- facet handling ---

  it('moves facet to json.facet key in body for downstream transform', () => {
    const facetObj = new Map([['categories', new Map([['type', 'terms'], ['field', 'cat']])]]);
    const ctx = buildCtx({ query: '*:*', facet: facetObj });
    request.apply(ctx);
    expect(ctx.body.has('json.facet')).toBe(true);
    expect(ctx.body.has('facet')).toBe(false);
  });

  // --- cleanup ---

  it('clears mapped keys from body after processing', () => {
    const ctx = buildCtx({
      query: 'title:java',
      limit: 10,
      offset: 0,
      sort: 'price asc',
      fields: 'id',
    });
    request.apply(ctx);
    expect(ctx.body.has('query')).toBe(false);
    expect(ctx.body.has('limit')).toBe(false);
    expect(ctx.body.has('offset')).toBe(false);
    expect(ctx.body.has('sort')).toBe(false);
    expect(ctx.body.has('fields')).toBe(false);
  });
});
