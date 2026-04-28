import { describe, it, expect } from 'vitest';
import {
  b64Encode, b64Decode, encodeCursorMark, decodeCursorMark,
  parseSolrSort, hasTiebreaker, request, response,
  SOLR_UNIQUE_KEY, OS_UNIQUE_KEY,
} from './cursor-pagination';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

// --- b64Encode / b64Decode ---

describe('b64Encode / b64Decode', () => {
  it('roundtrips simple string', () => {
    expect(b64Decode(b64Encode('hello'))).toBe('hello');
  });

  it('roundtrips empty string', () => {
    expect(b64Decode(b64Encode(''))).toBe('');
  });

  it('roundtrips JSON with special chars', () => {
    const json = '[44.99,"7"]';
    expect(b64Decode(b64Encode(json))).toBe(json);
  });

  it('roundtrips strings of length 1, 2, 3 (padding edge cases)', () => {
    expect(b64Decode(b64Encode('a'))).toBe('a');
    expect(b64Decode(b64Encode('ab'))).toBe('ab');
    expect(b64Decode(b64Encode('abc'))).toBe('abc');
  });

  it('produces correct padding', () => {
    expect(b64Encode('a')).toMatch(/=$/);
    expect(b64Encode('ab')).toMatch(/=$/);
    expect(b64Encode('abc')).not.toMatch(/=/);
  });

  it('matches known base64 values', () => {
    expect(b64Encode('hello')).toBe('aGVsbG8=');
    expect(b64Encode('[44.99,"7"]')).toBe('WzQ0Ljk5LCI3Il0=');
  });

  it('roundtrips multi-byte UTF-8 characters', () => {
    expect(b64Decode(b64Encode('caf\u00e9'))).toBe('caf\u00e9');
    expect(b64Decode(b64Encode('\u65e5\u672c\u8a9e'))).toBe('\u65e5\u672c\u8a9e');
  });
});

// --- encodeCursorMark / decodeCursorMark ---

describe('encodeCursorMark / decodeCursorMark', () => {
  it('roundtrips sort values with float and string', () => {
    const values = [44.99, '7'];
    expect(decodeCursorMark(encodeCursorMark(values))).toEqual(values);
  });

  it('roundtrips integer sort values', () => {
    const values = [3499, '3'];
    expect(decodeCursorMark(encodeCursorMark(values))).toEqual(values);
  });

  it('roundtrips single sort value', () => {
    const values = ['10'];
    expect(decodeCursorMark(encodeCursorMark(values))).toEqual(values);
  });

  it('roundtrips empty array', () => {
    expect(decodeCursorMark(encodeCursorMark([]))).toEqual([]);
  });
});

// --- parseSolrSort ---

describe('parseSolrSort', () => {
  it('parses single field sort', () => {
    const result = parseSolrSort('price asc');
    expect(result).toHaveLength(1);
    expect(result[0].get('price')).toBe('asc');
  });

  it('parses multi-field sort', () => {
    const result = parseSolrSort('price asc,id desc');
    expect(result).toHaveLength(2);
    expect(result[0].get('price')).toBe('asc');
    expect(result[1].get('_id')).toBe('desc');
  });

  it('maps id to _id using uniqueKey constants', () => {
    expect(SOLR_UNIQUE_KEY).toBe('id');
    expect(OS_UNIQUE_KEY).toBe('_id');
    const result = parseSolrSort('id asc');
    expect(result[0].has(OS_UNIQUE_KEY)).toBe(true);
    expect(result[0].has(SOLR_UNIQUE_KEY)).toBe(false);
  });

  it('handles space-separated sort (+ decoded by parseParams)', () => {
    const result = parseSolrSort('price asc,id desc');
    expect(result[0].get('price')).toBe('asc');
    expect(result[1].get('_id')).toBe('desc');
  });

  it('defaults direction to asc', () => {
    const result = parseSolrSort('price');
    expect(result[0].get('price')).toBe('asc');
  });

  it('handles spaces around commas', () => {
    const result = parseSolrSort('price asc , name desc');
    expect(result).toHaveLength(2);
    expect(result[0].get('price')).toBe('asc');
    expect(result[1].get('name')).toBe('desc');
  });
});

// --- hasTiebreaker ---

describe('hasTiebreaker', () => {
  it('returns true when _id is present', () => {
    const sort = [new Map([['price', 'asc']]), new Map([['_id', 'asc']])];
    expect(hasTiebreaker(sort)).toBe(true);
  });

  it('returns false when _id is absent', () => {
    const sort = [new Map([['price', 'asc']])];
    expect(hasTiebreaker(sort)).toBe(false);
  });
});

// --- request transform ---

function makeRequestCtx(params: Record<string, string>, body?: Map<string, any>): RequestContext {
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'products',
    params: new URLSearchParams(params),
    body: body ?? new Map(),
  };
}

describe('request transform', () => {
  it('matches when cursorMark present', () => {
    expect(request.match!(makeRequestCtx({ cursorMark: '*' }))).toBe(true);
  });

  it('does not match without cursorMark', () => {
    expect(request.match!(makeRequestCtx({ q: '*:*' }))).toBe(false);
  });

  it('matches in dual-mode (shim handles token splitting)', () => {
    const ctx = makeRequestCtx({ cursorMark: '*' });
    ctx.mode = 'dual';
    expect(request.match!(ctx)).toBe(true);
  });

  it('removes from and adds sort with tiebreaker for cursorMark=*', () => {
    const body = new Map<string, any>([['from', 5]]);
    const ctx = makeRequestCtx({ cursorMark: '*', sort: 'price asc' }, body);
    request.apply(ctx);

    expect(body.has('from')).toBe(false);
    expect(body.has('search_after')).toBe(false);
    const sort = body.get('sort') as Map<string, string>[];
    expect(sort).toHaveLength(2);
    expect(sort[1].get('_id')).toBe('asc');
  });

  it('decodes cursorMark into search_after', () => {
    const token = encodeCursorMark([44.99, '7']);
    const body = new Map<string, any>();
    const ctx = makeRequestCtx({ cursorMark: token, sort: 'price asc,id asc' }, body);
    request.apply(ctx);

    expect(body.get('search_after')).toEqual([44.99, '7']);
  });

  it('does not duplicate _id tiebreaker', () => {
    const body = new Map<string, any>();
    const ctx = makeRequestCtx({ cursorMark: '*', sort: 'price asc,id asc' }, body);
    request.apply(ctx);

    expect((body.get('sort') as any[]).length).toBe(2);
  });

  it('defaults to _id asc when no sort', () => {
    const body = new Map<string, any>();
    const ctx = makeRequestCtx({ cursorMark: '*' }, body);
    request.apply(ctx);

    const sort = body.get('sort') as Map<string, string>[];
    expect(sort).toHaveLength(1);
    expect(sort[0].get('_id')).toBe('asc');
  });

  it('throws on malformed cursorMark token', () => {
    const body = new Map<string, any>();
    const ctx = makeRequestCtx({ cursorMark: 'not-valid-base64!', sort: 'price asc' }, body);
    expect(() => request.apply(ctx)).toThrow('Invalid cursorMark token');
  });
});

// --- response transform ---

function makeResponseCtx(params: Record<string, string>, responseBody: Map<string, any>): ResponseContext {
  return {
    request: new Map() as unknown as JavaMap,
    response: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'products',
    requestParams: new URLSearchParams(params),
    responseBody,
  };
}

describe('response transform', () => {
  it('matches when cursorMark in request', () => {
    expect(response.match!(makeResponseCtx({ cursorMark: '*' }, new Map()))).toBe(true);
  });

  it('does not match without cursorMark', () => {
    expect(response.match!(makeResponseCtx({}, new Map()))).toBe(false);
  });

  it('matches in dual-mode (shim handles token merging)', () => {
    const ctx = makeResponseCtx({ cursorMark: '*' }, new Map());
    ctx.mode = 'dual';
    expect(response.match!(ctx)).toBe(true);
  });

  it('encodes nextCursorMark from last hit sort', () => {
    const hits = new Map([['hits', [
      new Map([['sort', [34.99, '10']]]),
      new Map([['sort', [44.99, '7']]]),
    ]]]);
    const body = new Map<string, any>([['hits', hits]]);
    const ctx = makeResponseCtx({ cursorMark: '*' }, body);
    response.apply(ctx);

    const ncm = body.get('nextCursorMark');
    expect(decodeCursorMark(ncm)).toEqual([44.99, '7']);
  });

  it('returns same cursorMark on empty results (end signal)', () => {
    const token = encodeCursorMark([3499, '3']);
    const hits = new Map([['hits', []]]);
    const body = new Map<string, any>([['hits', hits]]);
    const ctx = makeResponseCtx({ cursorMark: token }, body);
    response.apply(ctx);

    expect(body.get('nextCursorMark')).toBe(token);
  });

  it('returns * when cursorMark=* and no results', () => {
    const hits = new Map([['hits', []]]);
    const body = new Map<string, any>([['hits', hits]]);
    const ctx = makeResponseCtx({ cursorMark: '*' }, body);
    response.apply(ctx);

    expect(body.get('nextCursorMark')).toBe('*');
  });
});
