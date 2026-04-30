import { describe, it, expect } from 'vitest';
import { request } from './query-q';
import type { RequestContext, JavaMap } from '../context';

/** Create a mock RequestContext for testing. */
function createMockContext(
  params: Record<string, string>,
  fieldTypes: ReadonlyMap<string, string> = new Map(),
): RequestContext {
  const body = new Map<string, any>();
  return {
    msg: new Map() as JavaMap,
    endpoint: 'select',
    collection: 'test',
    params: new URLSearchParams(params),
    body: body as JavaMap,
    fieldTypes,
  };
}

describe('query-q request transform', () => {
  it('has correct name', () => {
    expect(request.name).toBe('query-q');
  });

  it('sets query from translateQ result', () => {
    const ctx = createMockContext({ q: '*:*' });
    request.apply(ctx);
    expect(ctx.body.has('query')).toBe(true);
  });

  it('converts rows param to size', () => {
    const ctx = createMockContext({ q: '*:*', rows: '25' });
    request.apply(ctx);
    expect(ctx.body.get('size')).toBe(25);
  });

  it('converts start param to from', () => {
    const ctx = createMockContext({ q: '*:*', start: '10' });
    request.apply(ctx);
    expect(ctx.body.get('from')).toBe(10);
  });

  it('handles both rows and start together', () => {
    const ctx = createMockContext({ q: '*:*', rows: '50', start: '100' });
    request.apply(ctx);
    expect(ctx.body.get('size')).toBe(50);
    expect(ctx.body.get('from')).toBe(100);
  });

  it('does not set size when rows is absent', () => {
    const ctx = createMockContext({ q: '*:*' });
    request.apply(ctx);
    expect(ctx.body.has('size')).toBe(false);
  });

  it('does not set from when start is absent', () => {
    const ctx = createMockContext({ q: '*:*' });
    request.apply(ctx);
    expect(ctx.body.has('from')).toBe(false);
  });

  it('defaults to *:* when q is missing', () => {
    const ctx = createMockContext({});
    request.apply(ctx);
    // translateQ defaults to *:* which should produce some query
    expect(ctx.body.has('query')).toBe(true);
  });

  // --- fieldTypes threading ---

  it('uses match query when fieldTypes is empty (default)', () => {
    const ctx = createMockContext({ q: 'status:active' });
    request.apply(ctx);

    const query = ctx.body.get('query') as Map<string, any>;
    expect(query.has('match')).toBe(true);
  });

  it('uses term query when ctx.fieldTypes identifies field as non-text', () => {
    const fieldTypes = new Map([['status', 'solr.StrField']]);
    const ctx = createMockContext({ q: 'status:active' }, fieldTypes);
    request.apply(ctx);

    const query = ctx.body.get('query') as Map<string, any>;
    expect(query.has('term')).toBe(true);
    expect((query.get('term') as Map<string, any>).get('status')).toEqual(
      new Map([['value', 'active']]),
    );
  });

  it('uses match query when ctx.fieldTypes identifies field as TextField', () => {
    const fieldTypes = new Map([['title', 'solr.TextField']]);
    const ctx = createMockContext({ q: 'title:java' }, fieldTypes);
    request.apply(ctx);

    const query = ctx.body.get('query') as Map<string, any>;
    expect(query.has('match')).toBe(true);
  });

  it('uses match for unknown field even when fieldTypes is provided', () => {
    const fieldTypes = new Map([['other_field', 'solr.StrField']]);
    const ctx = createMockContext({ q: 'title:java' }, fieldTypes);
    request.apply(ctx);

    // title not in fieldTypes → safe fallback to match
    const query = ctx.body.get('query') as Map<string, any>;
    expect(query.has('match')).toBe(true);
  });
});
