import { describe, it, expect } from 'vitest';
import { request, response } from './update-router';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

function buildCtx(uri: string, body: Map<string, any>): RequestContext {
  const msg = new Map<string, any>([
    ['URI', uri],
    ['method', 'POST'],
    ['headers', new Map()],
  ]) as unknown as JavaMap;
  return {
    msg,
    endpoint: 'update',
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    params: new URLSearchParams(),
    body: body as unknown as JavaMap,
  };
}

function deleteBody(id: any): Map<string, any> {
  return new Map([['delete', new Map([['id', id]])]]);
}

function deleteByQueryBody(query: string): Map<string, any> {
  return new Map([['delete', new Map([['query', query]])]]);
}

function addBody(doc: Record<string, any>): Map<string, any> {
  return new Map([['add', new Map([['doc', new Map(Object.entries(doc))]])]]);
}

describe('update-router request', () => {
  // --- routing ---

  it('routes /update/json/docs to update-doc handler', () => {
    const body = new Map<string, any>([['id', '1'], ['title', 'hello']]);
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update/json/docs'],
      ['method', 'POST'],
      ['headers', new Map()],
    ]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
    expect(ctx.msg.get('method')).toBe('PUT');
  });

  it('preserves body fields when routing /update/json/docs', () => {
    const body = new Map<string, any>([['id', '1'], ['title', 'hello'], ['price', 9.99]]);
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update/json/docs'],
      ['method', 'POST'],
      ['headers', new Map()],
    ]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    request.apply(ctx);
    expect(ctx.body.get('title')).toBe('hello');
    expect(ctx.body.get('price')).toBe(9.99);
  });

  it('does not match /foo/update/json/docs123 as json docs path', () => {
    const body = new Map<string, any>([['id', '1']]);
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update/json/docs123'],
      ['method', 'POST'],
      ['headers', new Map()],
    ]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    // Should go through dispatchCommand, not json docs path — will throw since body has no command
    expect(() => request.apply(ctx)).toThrow('no recognized command');
  });

  it('throws on XML content type', () => {
    const body = new Map<string, any>([['delete', new Map([['id', '1']])]]);
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update'],
      ['method', 'POST'],
      ['headers', new Map([['Content-Type', 'text/xml']])],
    ]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    expect(() => request.apply(ctx)).toThrow('only JSON content type is supported');
  });

  it('throws on non-POST method', () => {
    const body = new Map<string, any>([['delete', new Map([['id', '1']])]]);
    const msg = new Map<string, any>([
      ['URI', '/solr/mycore/update'],
      ['method', 'GET'],
      ['headers', new Map()],
    ]) as unknown as JavaMap;
    const ctx: RequestContext = {
      msg, endpoint: 'update', collection: 'mycore',
      params: new URLSearchParams(), body: body as unknown as JavaMap,
    };
    expect(() => request.apply(ctx)).toThrow('only POST method is accepted');
  });

  // --- delete: happy path ---

  it('dispatches delete to DELETE /_doc/{id}', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody('1'));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
    expect(ctx.msg.get('method')).toBe('DELETE');
  });

  it('handles delete with special chars in id', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody('doc/with spaces'));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/doc%2Fwith%20spaces');
  });

  it('handles delete with numeric id', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody(42));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/42');
  });

  // --- delete: error cases ---

  it('throws on delete with empty id', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody(''));
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws on delete with null id', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody(null));
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws on delete with whitespace-only id', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody('   '));
    expect(() => request.apply(ctx)).toThrow('non-empty "id"');
  });

  it('throws on delete with unsupported _route_ field', () => {
    const body = new Map([['delete', new Map<string, any>([['id', '1'], ['_route_', 'shard1']])]]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow("unsupported field '_route_'");
  });

  it('throws on delete with unsupported version field', () => {
    const body = new Map([['delete', new Map<string, any>([['id', '1'], ['version', 123]])]]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow("unsupported field 'version'");
  });

  it('handles delete with id containing reserved chars (/, ?, #)', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteBody('doc/1?q=x#frag'));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/doc%2F1%3Fq%3Dx%23frag');
  });

  it('handles delete with very long id', () => {
    const longId = 'a'.repeat(500);
    const ctx = buildCtx('/solr/mycore/update', deleteBody(longId));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe(`/mycore/_doc/${longId}`);
  });

  it('throws on delete-by-query', () => {
    const ctx = buildCtx('/solr/mycore/update', deleteByQueryBody('title:old'));
    expect(() => request.apply(ctx)).toThrow('delete-by-query is not supported');
  });

  it('throws on invalid delete format (not a Map)', () => {
    const ctx = buildCtx('/solr/mycore/update', new Map([['delete', 'invalid']]));
    expect(() => request.apply(ctx)).toThrow('invalid command format');
  });

  // --- add: happy path ---

  it('dispatches add to PUT /_doc/{id}', () => {
    const ctx = buildCtx('/solr/mycore/update', addBody({ id: '1', title: 'hello' }));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
    expect(ctx.msg.get('method')).toBe('PUT');
  });

  it('handles add with multiple fields', () => {
    const ctx = buildCtx('/solr/mycore/update', addBody({ id: '1', title: 'hi', price: 9.99 }));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  it('handles add with numeric id', () => {
    const ctx = buildCtx('/solr/mycore/update', addBody({ id: 42, title: 'test' }));
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/42');
  });

  // --- add: error cases ---

  it('throws on add without doc field', () => {
    const body = new Map<string, any>([['add', new Map([['id', '1']])]]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow('must contain a "doc" field');
  });

  it('throws on invalid add format (not a Map)', () => {
    const body = new Map<string, any>([['add', 'invalid']]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow('invalid command format');
  });

  it('throws on add with doc missing id', () => {
    const ctx = buildCtx('/solr/mycore/update', addBody({ title: 'no id' }));
    expect(() => request.apply(ctx)).toThrow('must have an "id" field');
  });

  it('throws on add with boost', () => {
    const body = new Map<string, any>([
      ['add', new Map<string, any>([['doc', new Map([['id', '1']])], ['boost', 2]])],
    ]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow('document-level boost is not supported');
  });

  it('throws on add with overwrite=false', () => {
    const body = new Map<string, any>([
      ['add', new Map<string, any>([['doc', new Map([['id', '1']])], ['overwrite', false]])],
    ]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow('overwrite=false is not supported');
  });

  it('allows add with overwrite=true (default behavior)', () => {
    const body = new Map<string, any>([
      ['add', new Map<string, any>([['doc', new Map([['id', '1'], ['title', 'test']])], ['overwrite', true]])],
    ]);
    const ctx = buildCtx('/solr/mycore/update', body);
    request.apply(ctx);
    expect(ctx.msg.get('URI')).toBe('/mycore/_doc/1');
  });

  // --- general error cases ---

  it('throws on empty body', () => {
    const ctx = buildCtx('/solr/mycore/update', new Map());
    expect(() => request.apply(ctx)).toThrow('request body is empty or not JSON');
  });

  it('throws on unrecognized command with keys in message', () => {
    const ctx = buildCtx('/solr/mycore/update', new Map([['unknown', new Map()]]));
    expect(() => request.apply(ctx)).toThrow(/no recognized command.*keys:.*unknown/);
  });

  it('throws on valid JSON body with no command keys', () => {
    const ctx = buildCtx('/solr/mycore/update', new Map([['foo', 'bar'], ['baz', 123]]));
    expect(() => request.apply(ctx)).toThrow(/no recognized command.*keys:.*foo.*baz/);
  });

  it('throws on mixed commands (delete + add)', () => {
    const body = new Map<string, any>([
      ['delete', new Map([['id', '1']])],
      ['add', new Map([['doc', new Map([['id', '2']])]])],
    ]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow('mixed commands');
  });

  it('throws on unsupported commit command', () => {
    const ctx = buildCtx('/solr/mycore/update', new Map([['commit', new Map()]]));
    expect(() => request.apply(ctx)).toThrow('command is not supported yet');
  });

  it('throws on unsupported optimize command', () => {
    const ctx = buildCtx('/solr/mycore/update', new Map([['optimize', new Map()]]));
    expect(() => request.apply(ctx)).toThrow('command is not supported yet');
  });

  it('throws on array of deletes (JS array)', () => {
    // Use a real JS array wrapped in a Map
    const body = new Map<string, any>([['delete', [{ id: '1' }, { id: '2' }]]]);
    const ctx = buildCtx('/solr/mycore/update', body);
    expect(() => request.apply(ctx)).toThrow('array/bulk operations are not supported');
  });
});

// --- Response transform ---

function buildResponseCtx(body: Map<string, any>): ResponseContext {
  return {
    request: new Map() as unknown as JavaMap,
    response: new Map() as unknown as JavaMap,
    endpoint: 'update',
    collection: 'mycore',
    requestParams: new URLSearchParams(),
    responseBody: body as unknown as JavaMap,
  };
}

describe('update-router response', () => {
  it('matches OpenSearch _doc response', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'created']]);
    expect(response.match!(buildResponseCtx(body))).toBe(true);
  });

  it('does not match select response', () => {
    const body = new Map<string, any>([['hits', new Map()], ['took', 5]]);
    expect(response.match!(buildResponseCtx(body))).toBe(false);
  });

  it('does not match response missing _id', () => {
    const body = new Map<string, any>([['result', 'created']]);
    expect(response.match!(buildResponseCtx(body))).toBe(false);
  });

  it('does not match response missing result', () => {
    const body = new Map<string, any>([['_id', '1']]);
    expect(response.match!(buildResponseCtx(body))).toBe(false);
  });

  it('converts created to status 0', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'created'], ['_index', 'x'], ['_shards', new Map()]]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
    expect(ctx.responseBody.has('_index')).toBe(false);
    expect(ctx.responseBody.has('_shards')).toBe(false);
    expect(ctx.responseBody.has('_id')).toBe(false);
    expect(ctx.responseBody.has('result')).toBe(false);
  });

  it('converts updated to status 0', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'updated']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
  });

  it('converts deleted to status 0', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'deleted']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
  });

  it('converts not_found to status 0', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'not_found']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
  });

  it('sets status 1 for noop result', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'noop']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
  });

  it('sets status 1 for unexpected result value', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'something_unexpected']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
  });

  it('sets QTime to 0', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'deleted']]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('QTime')).toBe(0);
  });

  it('clears all OpenSearch fields from response', () => {
    const body = new Map<string, any>([
      ['_id', '1'], ['result', 'created'], ['_index', 'x'],
      ['_version', 1], ['_shards', new Map()], ['_seq_no', 0], ['_primary_term', 1],
    ]);
    const ctx = buildResponseCtx(body);
    response.apply(ctx);
    // Only responseHeader should remain
    expect(ctx.responseBody.size).toBe(1);
    expect(ctx.responseBody.has('responseHeader')).toBe(true);
  });
});
