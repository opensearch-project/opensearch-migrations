import { describe, it, expect } from 'vitest';
import { request, paramRules } from './minimum-match';
import type { RequestContext, JavaMap } from '../context';

/** Build a mock context with a pre-built bool query in the body. */
function createCtx(params: Record<string, string>, query?: Map<string, any>): RequestContext {
  const body = new Map<string, any>();
  if (query) body.set('query', query);
  return {
    msg: new Map() as JavaMap,
    endpoint: 'select',
    collection: 'test',
    params: new URLSearchParams(params),
    body: body as JavaMap,
  };
}

function boolQuery(shouldCount = 2): Map<string, any> {
  const should = Array.from({ length: shouldCount }, (_, i) =>
    new Map([['match', new Map([['title', `term${i}`]])]]),
  );
  const boolMap = new Map<string, any>([['should', should]]);
  return new Map([['bool', boolMap]]);
}

describe('minimum-match request transform', () => {
  it('has correct name', () => {
    expect(request.name).toBe('minimum-match');
  });

  // --- match guard ---

  it('does not match when mm is absent', () => {
    const ctx = createCtx({ defType: 'dismax' });
    expect(request.match!(ctx)).toBe(false);
  });

  it('does not match when defType is absent (standard parser)', () => {
    const ctx = createCtx({ mm: '2' });
    expect(request.match!(ctx)).toBe(false);
  });

  it('does not match when defType is lucene', () => {
    const ctx = createCtx({ mm: '2', defType: 'lucene' });
    expect(request.match!(ctx)).toBe(false);
  });

  it('matches when defType=dismax and mm is present', () => {
    const ctx = createCtx({ mm: '2', defType: 'dismax' });
    expect(request.match!(ctx)).toBe(true);
  });

  it('matches when defType=edismax and mm is present', () => {
    const ctx = createCtx({ mm: '75%', defType: 'edismax' });
    expect(request.match!(ctx)).toBe(true);
  });

  // --- apply ---

  it('sets minimum_should_match on bool query — integer', () => {
    const query = boolQuery();
    const ctx = createCtx({ mm: '2', defType: 'dismax' }, query);
    request.apply(ctx);
    expect(query.get('bool').get('minimum_should_match')).toBe('2');
  });

  it('sets minimum_should_match — negative integer', () => {
    const query = boolQuery();
    const ctx = createCtx({ mm: '-1', defType: 'dismax' }, query);
    request.apply(ctx);
    expect(query.get('bool').get('minimum_should_match')).toBe('-1');
  });

  it('sets minimum_should_match — percentage', () => {
    const query = boolQuery();
    const ctx = createCtx({ mm: '75%', defType: 'edismax' }, query);
    request.apply(ctx);
    expect(query.get('bool').get('minimum_should_match')).toBe('75%');
  });

  it('sets minimum_should_match — negative percentage', () => {
    const query = boolQuery();
    const ctx = createCtx({ mm: '-25%', defType: 'dismax' }, query);
    request.apply(ctx);
    expect(query.get('bool').get('minimum_should_match')).toBe('-25%');
  });

  it('sets minimum_should_match — conditional expression', () => {
    const query = boolQuery();
    const ctx = createCtx({ mm: '3<90%', defType: 'dismax' }, query);
    request.apply(ctx);
    expect(query.get('bool').get('minimum_should_match')).toBe('3<90%');
  });

  it('sets minimum_should_match — multiple conditionals', () => {
    const query = boolQuery();
    const ctx = createCtx({ mm: '2<-25% 9<-3', defType: 'dismax' }, query);
    request.apply(ctx);
    expect(query.get('bool').get('minimum_should_match')).toBe('2<-25% 9<-3');
  });

  // --- no-op cases ---

  it('no-ops when query is match_all (no bool)', () => {
    const query = new Map([['match_all', new Map()]]);
    const ctx = createCtx({ mm: '2', defType: 'dismax' }, query);
    request.apply(ctx);
    expect(query.has('bool')).toBe(false);
  });

  it('no-ops when body has no query', () => {
    const ctx = createCtx({ mm: '2', defType: 'dismax' });
    request.apply(ctx);
    expect(ctx.body.has('query')).toBe(false);
  });
});

describe('minimum-match paramRules', () => {
  const pattern = new RegExp(paramRules[0].pattern);

  it.each(['3', '-2', '75%', '-25%', '3<90%', '2<-25% 9<-3'])(
    'allows valid mm value: %s',
    (val) => expect(pattern.test(val)).toBe(false),
  );

  it.each(['abc', '2a', 'foo%', '75%bar'])(
    'rejects invalid mm value: %s',
    (val) => expect(pattern.test(val)).toBe(true),
  );
});
