import { describe, it, expect } from 'vitest';
import { request, response, isRefreshResponse } from './update-commit';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

function buildCtx(): RequestContext {
  const msg = new Map<string, any>([
    ['URI', '/solr/mycore/update'],
    ['method', 'POST'],
    ['headers', new Map()],
  ]) as unknown as JavaMap;
  return {
    msg,
    endpoint: 'update',
    collection: 'mycore',
    params: new URLSearchParams(),
    body: new Map([['commit', new Map()]]) as unknown as JavaMap,
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

describe('update-commit request', () => {
  it('rewrites URI to /_refresh with POST', () => {
    const ctx = buildCtx();
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_refresh');
    expect(ctx.msg.get('method')).toBe('POST');
  });

  it('clears the body', () => {
    const ctx = buildCtx();
    request.apply(ctx);
    expect(ctx.body.size).toBe(0);
  });
});

describe('isRefreshResponse', () => {
  it('matches _refresh response shape', () => {
    const body = new Map<string, any>([['_shards', new Map([['total', 2], ['successful', 2], ['failed', 0]])]]);
    expect(isRefreshResponse(body)).toBe(true);
  });

  it('does not match _doc response', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'created']]);
    expect(isRefreshResponse(body)).toBe(false);
  });

  it('does not match _bulk response', () => {
    const body = new Map<string, any>([['items', []], ['took', 1]]);
    expect(isRefreshResponse(body)).toBe(false);
  });

  it('does not match _delete_by_query response', () => {
    const body = new Map<string, any>([['deleted', 5], ['total', 5], ['_shards', new Map()]]);
    expect(isRefreshResponse(body)).toBe(false);
  });

  it('does not match null', () => {
    expect(isRefreshResponse(null)).toBe(false);
  });
});

describe('update-commit response', () => {
  it('maps successful refresh to status 0', () => {
    const body = new Map<string, any>([['_shards', new Map([['total', 2], ['successful', 2], ['failed', 0]])]]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
    expect(ctx.responseBody.get('responseHeader').get('QTime')).toBe(0);
  });

  it('maps failed shards to status 1', () => {
    const body = new Map<string, any>([['_shards', new Map([['total', 2], ['successful', 1], ['failed', 1]])]]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
  });

  it('strips all OpenSearch fields', () => {
    const body = new Map<string, any>([['_shards', new Map([['total', 2], ['successful', 2], ['failed', 0]])]]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.size).toBe(1);
    expect(ctx.responseBody.has('responseHeader')).toBe(true);
    expect(ctx.responseBody.has('_shards')).toBe(false);
  });

  it('handles missing _shards gracefully (defaults to success)', () => {
    const body = new Map<string, any>([['_shards', 'not a map']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
  });
});
