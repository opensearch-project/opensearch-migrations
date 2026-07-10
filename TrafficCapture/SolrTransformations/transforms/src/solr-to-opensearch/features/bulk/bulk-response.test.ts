import { describe, it, expect } from 'vitest';
import { response, isBulkResponse } from './bulk-response';
import type { ResponseContext, JavaMap } from '../../context';

/** Build a minimal ResponseContext wrapping the given body. */
function buildCtx(body: Map<string, any>): ResponseContext {
  return {
    request: new Map() as unknown as JavaMap,
    response: new Map() as unknown as JavaMap,
    endpoint: 'update',
    collection: 'mycore',
    requestParams: new URLSearchParams(),
    responseBody: body as unknown as JavaMap,
  } as ResponseContext;
}

/** Build an items[] entry for operation `op` with the given details. */
function bulkItem(
  op: 'index' | 'create' | 'update' | 'delete',
  details: Record<string, any>,
): Map<string, any> {
  return new Map([[op, new Map<string, any>(Object.entries(details))]]);
}

describe('isBulkResponse', () => {
  it('matches response with items array', () => {
    const body = new Map<string, any>([['took', 5], ['errors', false], ['items', []]]);
    expect(isBulkResponse(body as unknown as JavaMap)).toBe(true);
  });

  it('does not match _doc response', () => {
    const body = new Map<string, any>([['_id', '1'], ['result', 'created']]);
    expect(isBulkResponse(body as unknown as JavaMap)).toBe(false);
  });

  it('does not match select response', () => {
    const body = new Map<string, any>([['hits', new Map()], ['took', 3]]);
    expect(isBulkResponse(body as unknown as JavaMap)).toBe(false);
  });

  it('does not match null / undefined', () => {
    expect(isBulkResponse(null as unknown as JavaMap)).toBe(false);
    expect(isBulkResponse(undefined as unknown as JavaMap)).toBe(false);
  });
});

describe('bulk-response.apply', () => {
  it('maps all-success to status 0 with QTime from took', () => {
    const body = new Map<string, any>([
      ['took', 42],
      ['errors', false],
      ['items', [
        bulkItem('index', { _index: 'mycore', _id: '1', status: 201 }),
        bulkItem('index', { _index: 'mycore', _id: '2', status: 200 }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);

    const header = ctx.responseBody.get('responseHeader');
    expect(header.get('status')).toBe(0);
    expect(header.get('QTime')).toBe(42);
    expect(ctx.responseBody.has('errors')).toBe(false);
  });

  it('maps any item failure to status 1 with errors array', () => {
    const body = new Map<string, any>([
      ['took', 7],
      ['errors', true],
      ['items', [
        bulkItem('index', { _index: 'mycore', _id: '1', status: 201 }),
        bulkItem('index', {
          _index: 'mycore',
          _id: '2',
          status: 400,
          error: new Map<string, any>([
            ['type', 'mapper_parsing_exception'],
            ['reason', 'failed to parse field [x]'],
          ]),
        }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);

    const header = ctx.responseBody.get('responseHeader');
    expect(header.get('status')).toBe(1);
    expect(header.get('QTime')).toBe(7);
    const errors = ctx.responseBody.get('errors');
    expect(Array.isArray(errors)).toBe(true);
    expect(errors).toHaveLength(1);
    const first = errors[0] as Map<string, any>;
    expect(first.get('id')).toBe('2');
    expect(first.get('status')).toBe(400);
    expect(first.get('type')).toBe('mapper_parsing_exception');
    expect(first.get('reason')).toBe('failed to parse field [x]');
  });

  it('treats error block without numeric status as failure', () => {
    const body = new Map<string, any>([
      ['took', 1],
      ['errors', true],
      ['items', [
        bulkItem('update', {
          _index: 'mycore',
          _id: 'x',
          error: new Map<string, any>([['type', 'version_conflict_engine_exception'], ['reason', 'conflict']]),
        }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);

    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
    expect(ctx.responseBody.get('errors')).toHaveLength(1);
  });

  it('handles all-failure response with multiple errors', () => {
    const body = new Map<string, any>([
      ['took', 3],
      ['errors', true],
      ['items', [
        bulkItem('delete', {
          _index: 'mycore',
          _id: '1',
          status: 404,
          error: new Map<string, any>([['type', 'not_found'], ['reason', 'missing']]),
        }),
        bulkItem('delete', {
          _index: 'mycore',
          _id: '2',
          status: 500,
          error: new Map<string, any>([['type', 'server_error'], ['reason', 'boom']]),
        }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);

    const errors = ctx.responseBody.get('errors');
    expect(errors).toHaveLength(2);
    expect((errors[0] as Map<string, any>).get('status')).toBe(404);
    expect((errors[1] as Map<string, any>).get('status')).toBe(500);
  });

  it('handles empty items array as success', () => {
    const body = new Map<string, any>([
      ['took', 0],
      ['errors', false],
      ['items', []],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
    expect(ctx.responseBody.has('errors')).toBe(false);
  });

  it('defaults QTime to 0 when took is missing', () => {
    const body = new Map<string, any>([
      ['errors', false],
      ['items', []],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('QTime')).toBe(0);
  });

  it('strips all original OpenSearch fields from the response', () => {
    const body = new Map<string, any>([
      ['took', 12],
      ['errors', false],
      ['items', [bulkItem('index', { _index: 'mycore', _id: '1', status: 201 })]],
      ['_shards', new Map()],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);

    // Only responseHeader should remain (no errors → no errors key)
    expect(ctx.responseBody.size).toBe(1);
    expect(ctx.responseBody.has('responseHeader')).toBe(true);
    expect(ctx.responseBody.has('took')).toBe(false);
    expect(ctx.responseBody.has('errors')).toBe(false);
    expect(ctx.responseBody.has('items')).toBe(false);
  });

  it('omits fields on per-item error when they are not present in source', () => {
    const body = new Map<string, any>([
      ['took', 1],
      ['errors', true],
      ['items', [
        bulkItem('update', {
          // intentionally no _index, no error block — only a failing status
          _id: '1',
          status: 409,
        }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);

    const errors = ctx.responseBody.get('errors');
    expect(errors).toHaveLength(1);
    const first = errors[0] as Map<string, any>;
    expect(first.has('index')).toBe(false);
    expect(first.get('id')).toBe('1');
    expect(first.get('status')).toBe(409);
    expect(first.has('type')).toBe(false);
    expect(first.has('reason')).toBe(false);
  });

  it('skips malformed items without crashing', () => {
    const body = new Map<string, any>([
      ['took', 1],
      ['errors', true],
      ['items', [
        null,
        'not an object',
        new Map(), // empty item — no op key
        bulkItem('index', { _index: 'mycore', _id: '1', status: 201 }),
      ]],
    ]);
    const ctx = buildCtx(body);
    // Should not throw
    expect(() => response.apply(ctx)).not.toThrow();
    // And should still report success for the one valid, passing item
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(0);
  });

  it('handles status as string (e.g. from non-standard proxy)', () => {
    const body = new Map<string, any>([
      ['took', 1],
      ['errors', true],
      ['items', [
        bulkItem('index', { _index: 'mycore', _id: '1', status: '400' }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);
    expect(ctx.responseBody.get('responseHeader').get('status')).toBe(1);
    const errors = ctx.responseBody.get('errors');
    expect(errors).toHaveLength(1);
    expect((errors[0] as Map<string, any>).get('status')).toBe(400);
  });

  it('handles numeric _id and _index in error details', () => {
    const body = new Map<string, any>([
      ['took', 1],
      ['errors', true],
      ['items', [
        bulkItem('index', {
          _index: 123,
          _id: 456,
          status: 500,
          error: new Map<string, any>([['type', 'err'], ['reason', 'boom']]),
        }),
      ]],
    ]);
    const ctx = buildCtx(body);
    response.apply(ctx);
    const errors = ctx.responseBody.get('errors');
    expect(errors).toHaveLength(1);
    // numeric values should not crash — toStringOrUndefined converts them
    const first = errors[0] as Map<string, any>;
    expect(first.get('index')).toBe('123');
    expect(first.get('id')).toBe('456');
  });

  it('match predicate works when called directly on bulk-response export', () => {
    const body = new Map<string, any>([['took', 1], ['errors', false], ['items', []]]);
    const ctx = buildCtx(body);
    expect(response.match!(ctx)).toBe(true);
  });
});
