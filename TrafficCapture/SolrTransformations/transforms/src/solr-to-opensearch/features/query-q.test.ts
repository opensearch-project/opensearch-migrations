import { describe, it, expect } from 'vitest';
import { request } from './query-q';
import type { RequestContext, JavaMap } from '../context';

function createMockContext(params: Record<string, string>): RequestContext {
  const urlParams = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    urlParams.set(k, v);
  }
  return {
    endpoint: 'select',
    collection: 'test',
    params: urlParams,
    body: new Map() as unknown as JavaMap,
    msg: new Map() as unknown as JavaMap,
  };
}

function mapToObject(map: JavaMap): unknown {
  if (!(map instanceof Map)) return map;
  const obj: Record<string, unknown> = {};
  for (const [k, v] of map.entries()) {
    if (v instanceof Map) {
      obj[k] = mapToObject(v);
    } else if (Array.isArray(v)) {
      obj[k] = v.map((item) => (item instanceof Map ? mapToObject(item) : item));
    } else {
      obj[k] = v;
    }
  }
  return obj;
}

function applyAndGetQuery(q: string): unknown {
  const ctx = createMockContext({ q });
  request.apply(ctx);
  return mapToObject(ctx.body.get('query') as JavaMap);
}

function applyAndGetBody(params: Record<string, string>): Record<string, unknown> {
  const ctx = createMockContext(params);
  request.apply(ctx);
  const result: Record<string, unknown> = {};
  for (const [k, v] of (ctx.body as unknown as Map<string, unknown>).entries()) {
    result[k] = v instanceof Map ? mapToObject(v as JavaMap) : v;
  }
  return result;
}

describe('query-q', () => {
  // Query parsing tests
  it('match_all for *:*', () => {
    expect(applyAndGetQuery('*:*')).toEqual({ match_all: {} });
  });

  it('match_all for empty query', () => {
    expect(applyAndGetQuery('')).toEqual({ match_all: {} });
  });

  it('simple term query', () => {
    expect(applyAndGetQuery('title:search')).toEqual({ term: { title: 'search' } });
  });

  it('implicit field query', () => {
    expect(applyAndGetQuery('java')).toEqual({ query_string: { query: 'java' } });
  });

  it('AND boolean query', () => {
    expect(applyAndGetQuery('title:search AND author:john')).toEqual({
      bool: {
        must: [{ term: { title: 'search' } }, { term: { author: 'john' } }],
      },
    });
  });

  it('OR boolean query', () => {
    expect(applyAndGetQuery('title:test OR content:example')).toEqual({
      bool: {
        should: [{ term: { title: 'test' } }, { term: { content: 'example' } }],
      },
    });
  });

  it('NOT boolean query', () => {
    expect(applyAndGetQuery('title:search NOT status:draft')).toEqual({
      bool: {
        must: [{ term: { title: 'search' } }],
        must_not: [{ term: { status: 'draft' } }],
      },
    });
  });

  it('inclusive range query', () => {
    expect(applyAndGetQuery('price:[10 TO 100]')).toEqual({
      range: { price: { gte: '10', lte: '100' } },
    });
  });

  it('exclusive range query', () => {
    expect(applyAndGetQuery('price:{10 TO 100}')).toEqual({
      range: { price: { gt: '10', lt: '100' } },
    });
  });

  it('phrase query', () => {
    expect(applyAndGetQuery('title:"hello world"')).toEqual({
      match_phrase: { title: 'hello world' },
    });
  });

  it('boosted term query', () => {
    expect(applyAndGetQuery('title:search^2')).toEqual({
      term: { title: { value: 'search', boost: 2 } },
    });
  });

  it('boosted implicit field query', () => {
    expect(applyAndGetQuery('java^3')).toEqual({
      query_string: { query: 'java', boost: 3 },
    });
  });

  it('boosted phrase query', () => {
    expect(applyAndGetQuery('title:"hello world"^2')).toEqual({
      match_phrase: { title: { query: 'hello world', boost: 2 } },
    });
  });

  it('required prefix (+)', () => {
    expect(applyAndGetQuery('+title:required -status:excluded')).toEqual({
      bool: {
        must: [{ term: { title: 'required' } }],
        must_not: [{ term: { status: 'excluded' } }],
      },
    });
  });

  it('prefix on implicit field (+foo -bar)', () => {
    expect(applyAndGetQuery('+foo -bar')).toEqual({
      bool: {
        must: [{ query_string: { query: 'foo' } }],
        must_not: [{ query_string: { query: 'bar' } }],
      },
    });
  });

  // Pagination tests (rows/start)
  it('rows param converts to size', () => {
    const body = applyAndGetBody({ q: '*:*', rows: '25' });
    expect(body.size).toBe(25);
  });

  it('start param converts to from', () => {
    const body = applyAndGetBody({ q: '*:*', start: '10' });
    expect(body.from).toBe(10);
  });

  it('rows and start together', () => {
    const body = applyAndGetBody({ q: '*:*', rows: '50', start: '100' });
    expect(body.size).toBe(50);
    expect(body.from).toBe(100);
  });
});
