import { describe, it, expect } from 'vitest';
import { request, response, isDeleteByQueryResponse } from './delete-by-query';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

function buildCtx(
  query: string,
  commitParam?: string,
): RequestContext {
  const uri = '/solr/mycore/update';
  const params = new URLSearchParams();
  if (commitParam) params.set('commit', commitParam);
  const msg = new Map<string, any>([
    ['URI', uri],
    ['method', 'POST'],
    ['headers', new Map()],
  ]) as unknown as JavaMap;
  return {
    msg,
    endpoint: 'update',
    collection: 'mycore',
    params,
    body: new Map<string, any>([['query', query]]) as unknown as JavaMap,
  } as RequestContext;
}

function buildResponseCtx(body: Map<string, any>): ResponseContext {
  return {
    request: new Map() as unknown as JavaMap,
    response: new Map() as unknown as JavaMap,
    endpoint: 'update',
    collection: 'mycore',
    requestParams: new URLSearchParams(),
    responseBody: body as unknown as JavaMap,
  } as ResponseContext;
}

describe('delete-by-query request', () => {
  it('translates simple query to _delete_by_query with DSL', () => {
    const ctx = buildCtx('title:hello');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toContain('/mycore/_delete_by_query');
    expect(ctx.msg.get('URI')).toContain('wait_for_completion=true');
    expect(ctx.msg.get('method')).toBe('POST');
    expect(ctx.body.has('query')).toBe(true);
  });

  it('translates match-all *:* query', () => {
    const ctx = buildCtx('*:*');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toContain('/mycore/_delete_by_query');
    expect(ctx.body.has('query')).toBe(true);
  });

  it('adds refresh=true when commit=true', () => {
    const ctx = buildCtx('title:old', 'true');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toContain('refresh=true');
    expect(ctx.msg.get('URI')).toContain('wait_for_completion=true');
  });

  it('omits refresh when no commit param', () => {
    const ctx = buildCtx('title:old');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).not.toContain('refresh=true');
    expect(ctx.msg.get('URI')).toContain('wait_for_completion=true');
  });

  it('throws on empty query string', () => {
    const ctx = buildCtx('');
    expect(() => request.apply(ctx)).toThrow('query string is empty');
  });

  it('throws on null query', () => {
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update'],
      ['method', 'POST'],
      ['headers', new Map()],
    ]) as unknown as JavaMap;
    const ctx = {
      msg,
      endpoint: 'update' as const,
      collection: 'mycore',
      params: new URLSearchParams(),
      body: new Map<string, any>([['query', null]]) as unknown as JavaMap,
    } as RequestContext;
    expect(() => request.apply(ctx)).toThrow('query string is empty');
  });

  it('throws on whitespace-only query', () => {
    const ctx = buildCtx('   ');
    expect(() => request.apply(ctx)).toThrow('query string is empty');
  });

  it('throws on unparseable query (fail-fast mode)', () => {
    // A query with unbalanced brackets should fail to parse
    const ctx = buildCtx('title:[invalid');
    expect(() => request.apply(ctx)).toThrow();
  });

  it('trims whitespace from query before translation', () => {
    const ctx = buildCtx('  title:hello  ');
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toContain('/mycore/_delete_by_query');
    expect(ctx.body.has('query')).toBe(true);
  });
});

describe('isDeleteByQueryResponse', () => {
  it('matches _delete_by_query response shape', () => {
    const body = new Map<string, any>([['took', 5], ['total', 3], ['deleted', 3]]);
    expect(isDeleteByQueryResponse(body)).toBe(true);
  });

  it('does not match _doc response', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'created']]);
    expect(isDeleteByQueryResponse(body)).toBe(false);
  });

  it('does not match select response', () => {
    const body = new Map<string, any>([['hits', new Map()], ['took', 3]]);
    expect(isDeleteByQueryResponse(body)).toBe(false);
  });

  it('does not match null', () => {
    expect(isDeleteByQueryResponse(null)).toBe(false);
  });
});

describe('delete-by-query response', () => {
  it('maps all-success to status 0 with QTime from took', () => {
    const body = new Map<string, any>([
      ['took', 42], ['total', 5], ['deleted', 5],
      ['batches', 1], ['version_conflicts', 0], ['failures', []],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    const header = ctx.responseBody.get('responseHeader');
    expect(header.get('status')).toBe(0);
    expect(header.get('QTime')).toBe(42);
  });

  it('maps version_conflicts > 0 to status 1', () => {
    const body = new Map<string, any>([
      ['took', 10], ['total', 5], ['deleted', 3],
      ['version_conflicts', 2], ['failures', []],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
  });

  it('maps non-empty failures to status 1', () => {
    const body = new Map<string, any>([
      ['took', 10], ['total', 5], ['deleted', 4],
      ['version_conflicts', 0], ['failures', [{ index: 'mycore', id: '1' }]],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
  });

  it('maps deleted < total to status 1', () => {
    const body = new Map<string, any>([
      ['took', 10], ['total', 5], ['deleted', 3],
      ['version_conflicts', 0], ['failures', []],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
  });

  it('strips all OpenSearch fields from response', () => {
    const body = new Map<string, any>([
      ['took', 5], ['total', 2], ['deleted', 2],
      ['batches', 1], ['version_conflicts', 0], ['failures', []],
      ['noops', 0], ['retries', new Map()],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.size).toBe(1);
    expect(ctx.responseBody.has('responseHeader')).toBe(true);
  });

  it('defaults QTime to 0 when took is missing', () => {
    const body = new Map<string, any>([['total', 0], ['deleted', 0]]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('QTime')).toBe(0);
  });

  it('zero total and zero deleted is success', () => {
    const body = new Map<string, any>([
      ['took', 1], ['total', 0], ['deleted', 0],
      ['version_conflicts', 0], ['failures', []],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
  });
});
