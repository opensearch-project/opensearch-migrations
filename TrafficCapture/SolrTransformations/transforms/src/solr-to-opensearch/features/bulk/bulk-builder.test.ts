import { describe, it, expect } from 'vitest';
import { buildBulkAction, setBulkRequest } from './bulk-builder';
import type { RequestContext, JavaMap } from '../../context';

/** Build a minimal RequestContext suitable for exercising setBulkRequest. */
function buildCtx(uri = '/solr/mycore/update'): RequestContext {
  const msg = new Map<string, any>([
    ['URI', uri],
    ['method', 'POST'],
    ['headers', new Map<string, any>([['Content-Type', 'application/json']])],
  ]) as unknown as JavaMap;
  return {
    msg,
    endpoint: 'update',
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    params: new URLSearchParams(),
    body: new Map() as unknown as JavaMap,
  } as RequestContext;
}

describe('buildBulkAction', () => {
  describe('index', () => {
    it('returns [action, doc] with _index and _id when id provided', () => {
      const out = buildBulkAction('index', {
        index: 'mycore',
        id: '1',
        doc: new Map([['title', 'hello']]),
      });
      expect(out).toHaveLength(2);
      const meta = out[0].get('index') as Map<string, unknown>;
      expect(meta.get('_index')).toBe('mycore');
      expect(meta.get('_id')).toBe('1');
      expect((out[1]).get('title')).toBe('hello');
    });

    it('omits _id when id is absent (OpenSearch auto-generates)', () => {
      const out = buildBulkAction('index', {
        index: 'mycore',
        doc: new Map([['title', 'hello']]),
      });
      const meta = out[0].get('index') as Map<string, unknown>;
      expect(meta.has('_index')).toBe(true);
      expect(meta.has('_id')).toBe(false);
    });

    it('coerces numeric id to string for _id metadata', () => {
      const out = buildBulkAction('index', {
        index: 'mycore',
        id: 42,
        doc: new Map([['title', 'hello']]),
      });
      const meta = out[0].get('index') as Map<string, unknown>;
      expect(meta.get('_id')).toBe('42');
    });

    it('throws when doc is missing', () => {
      expect(() => buildBulkAction('index', { index: 'mycore', id: '1' })).toThrow('"doc" is required');
    });

    it('throws when index is missing', () => {
      expect(() =>
        buildBulkAction('index', { index: '', id: '1', doc: new Map([['x', 1]]) }),
      ).toThrow('"index" is required');
    });
  });

  describe('create', () => {
    it('returns [action, doc] with _id required', () => {
      const out = buildBulkAction('create', {
        index: 'mycore',
        id: '1',
        doc: new Map([['title', 'hello']]),
      });
      expect(out).toHaveLength(2);
      expect((out[0].get('create') as Map<string, unknown>).get('_id')).toBe('1');
    });

    it('throws when id is missing', () => {
      expect(() =>
        buildBulkAction('create', { index: 'mycore', doc: new Map([['x', 1]]) }),
      ).toThrow('"id" is required');
    });

    it('throws when doc is missing', () => {
      expect(() =>
        buildBulkAction('create', { index: 'mycore', id: '1' }),
      ).toThrow('"doc" is required');
    });
  });

  describe('update', () => {
    it('returns [action, updatePayload] with _id required', () => {
      const updatePayload = new Map<string, unknown>([
        ['doc', new Map([['title', 'new']])],
      ]);
      const out = buildBulkAction('update', {
        index: 'mycore',
        id: '1',
        doc: updatePayload,
      });
      expect(out).toHaveLength(2);
      expect((out[0].get('update') as Map<string, unknown>).get('_id')).toBe('1');
      expect(out[1]).toBe(updatePayload);
    });

    it('throws when id is missing', () => {
      expect(() =>
        buildBulkAction('update', {
          index: 'mycore',
          doc: new Map([['doc', new Map()]]),
        }),
      ).toThrow('"id" is required');
    });

    it('throws when doc is missing', () => {
      expect(() =>
        buildBulkAction('update', { index: 'mycore', id: '1' }),
      ).toThrow('"doc" is required');
    });
  });

  describe('delete', () => {
    it('returns [action] only — no doc', () => {
      const out = buildBulkAction('delete', { index: 'mycore', id: '1' });
      expect(out).toHaveLength(1);
      const meta = out[0].get('delete') as Map<string, unknown>;
      expect(meta.get('_index')).toBe('mycore');
      expect(meta.get('_id')).toBe('1');
    });

    it('throws when id is missing', () => {
      expect(() => buildBulkAction('delete', { index: 'mycore' })).toThrow('"id" is required');
    });

    it('throws when doc is accidentally passed', () => {
      expect(() =>
        buildBulkAction('delete', {
          index: 'mycore',
          id: '1',
          doc: new Map([['x', 1]]),
        }),
      ).toThrow('must not include "doc"');
    });
  });
});

describe('setBulkRequest', () => {
  it('rewrites URI to /{collection}/_bulk and sets POST', () => {
    const ctx = buildCtx();
    const actions = buildBulkAction('index', {
      index: 'mycore',
      id: '1',
      doc: new Map([['title', 'hi']]),
    });
    setBulkRequest(ctx, 'mycore', actions);
    expect(ctx.msg.get('URI')).toBe('/mycore/_bulk');
    expect(ctx.msg.get('method')).toBe('POST');
  });

  it('adds ?refresh=true when options.refresh is true', () => {
    const ctx = buildCtx();
    const actions = buildBulkAction('delete', { index: 'mycore', id: '1' });
    setBulkRequest(ctx, 'mycore', actions, { refresh: true });
    expect(ctx.msg.get('URI')).toBe('/mycore/_bulk?refresh=true');
  });

  it('replaces Content-Type with application/x-ndjson (case-safe)', () => {
    const ctx = buildCtx();
    // Pre-populate with a mixed-case header to mirror real ingress
    const headers = ctx.msg.get('headers');
    headers.delete('Content-Type');
    headers.set('Content-Type', 'application/json');
    const actions = buildBulkAction('delete', { index: 'mycore', id: '1' });
    setBulkRequest(ctx, 'mycore', actions);
    expect(headers.has('Content-Type')).toBe(false);
    expect(headers.get('content-type')).toBe('application/x-ndjson');
  });

  it('writes actions to payload.inlinedJsonSequenceBodies', () => {
    const ctx = buildCtx();
    const actions = [
      ...buildBulkAction('index', {
        index: 'mycore',
        id: '1',
        doc: new Map([['title', 'a']]),
      }),
      ...buildBulkAction('index', {
        index: 'mycore',
        id: '2',
        doc: new Map([['title', 'b']]),
      }),
    ];
    setBulkRequest(ctx, 'mycore', actions);
    const payload = ctx.msg.get('payload');
    const seq = payload.get('inlinedJsonSequenceBodies');
    expect(seq).toBe(actions);
    expect(seq).toHaveLength(4); // 2 ops × (action + doc)
  });

  it('clears sibling inlinedJsonBody to preserve single-body invariant', () => {
    const ctx = buildCtx();
    // Pre-populate inlinedJsonBody as if a prior transform wrote it
    const payload = new Map<string, unknown>([['inlinedJsonBody', new Map([['stale', true]])]]);
    ctx.msg.set('payload', payload);
    const actions = buildBulkAction('delete', { index: 'mycore', id: '1' });
    setBulkRequest(ctx, 'mycore', actions);
    expect(payload.has('inlinedJsonBody')).toBe(false);
    expect(payload.has('inlinedJsonSequenceBodies')).toBe(true);
  });

  it('clears ctx.body so request.transform.ts does not re-populate inlinedJsonBody', () => {
    const ctx = buildCtx();
    ctx.body = new Map([['original', 'body']]) as unknown as JavaMap;
    const actions = buildBulkAction('delete', { index: 'mycore', id: '1' });
    setBulkRequest(ctx, 'mycore', actions);
    expect(ctx.body.size).toBe(0);
  });

  it('creates a payload map when one does not exist yet', () => {
    const ctx = buildCtx();
    // Brand-new msg without a payload key
    expect(ctx.msg.get('payload')).toBeUndefined();
    const actions = buildBulkAction('delete', { index: 'mycore', id: '1' });
    setBulkRequest(ctx, 'mycore', actions);
    const payload = ctx.msg.get('payload');
    expect(payload).toBeDefined();
    expect(payload.has('inlinedJsonSequenceBodies')).toBe(true);
  });

  it('throws when collection is empty', () => {
    const ctx = buildCtx();
    const actions = buildBulkAction('delete', { index: 'mycore', id: '1' });
    expect(() => setBulkRequest(ctx, '', actions)).toThrow('collection is required');
  });

  it('throws when actions array is empty', () => {
    const ctx = buildCtx();
    expect(() => setBulkRequest(ctx, 'mycore', [])).toThrow('non-empty array');
  });
});
