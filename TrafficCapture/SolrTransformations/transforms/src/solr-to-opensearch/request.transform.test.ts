/**
 * Unit tests for request.transform.ts.
 *
 * resolveFieldTypes() and resolveSolrConfig() are tested directly.
 * transform() is tested for the behaviors that don't depend on GraalVM bindings
 * (unknown endpoint passthrough, body writeback, basic query translation).
 * The fieldTypes → term/match behavior is covered in query-q.test.ts.
 */
import { describe, it, expect } from 'vitest';
import { resolveFieldTypes, resolveSolrConfig, transform } from './request.transform';
import type { JavaMap } from './context';

/** Build a minimal JavaMap message for a given URI. */
function mockMsg(uri: string): JavaMap {
  const data = new Map<string, any>([['URI', uri]]);
  return data as unknown as JavaMap;
}

describe('resolveFieldTypes', () => {
  it('returns empty map when bindings is undefined', () => {
    const result = resolveFieldTypes(undefined);
    expect(result.size).toBe(0);
  });

  it('returns empty map when bindings has no fieldTypes key', () => {
    const result = resolveFieldTypes({ solrConfig: {} });
    expect(result.size).toBe(0);
  });

  it('returns empty map when bindings.fieldTypes is empty object', () => {
    const result = resolveFieldTypes({ fieldTypes: {} });
    expect(result.size).toBe(0);
  });

  it('converts fieldTypes object to a ReadonlyMap', () => {
    const result = resolveFieldTypes({
      fieldTypes: {
        id:       'solr.StrField',
        title:    'solr.TextField',
        status:   'solr.StrField',
      },
    });

    expect(result.size).toBe(3);
    expect(result.get('id')).toBe('solr.StrField');
    expect(result.get('title')).toBe('solr.TextField');
    expect(result.get('status')).toBe('solr.StrField');
  });

  it('returns empty map for unknown field', () => {
    const result = resolveFieldTypes({ fieldTypes: { status: 'solr.StrField' } });
    expect(result.get('unknown_field')).toBeUndefined();
  });

  it('returns the same empty map singleton when no fieldTypes (no allocation)', () => {
    const a = resolveFieldTypes(undefined);
    const b = resolveFieldTypes({});
    // Both should be the shared EMPTY_FIELD_TYPES constant
    expect(a).toBe(b);
  });

  it('handles fieldTypes with TextField class correctly', () => {
    const result = resolveFieldTypes({
      fieldTypes: { body: 'solr.TextField' },
    });
    const cls = result.get('body');
    expect(cls).toBeDefined();
    expect(cls!.includes('TextField')).toBe(true);
  });

  it('handles fieldTypes with non-text class correctly', () => {
    const result = resolveFieldTypes({
      fieldTypes: { price: 'solr.FloatPointField' },
    });
    const cls = result.get('price');
    expect(cls).toBeDefined();
    expect(cls!.includes('TextField')).toBe(false);
  });
});

describe('resolveSolrConfig', () => {
  it('returns undefined when bindings is undefined', () => {
    expect(resolveSolrConfig(undefined)).toBeUndefined();
  });

  it('returns undefined when bindings has no solrConfig key', () => {
    expect(resolveSolrConfig({ fieldTypes: {} })).toBeUndefined();
  });

  it('returns the solrConfig object when present', () => {
    const config = { '/select': { defaults: { df: 'title' } } };
    const result = resolveSolrConfig({ solrConfig: config });
    expect(result).toBe(config);
  });

  it('returns the same reference (not a copy)', () => {
    const config = { '/select': { defaults: { df: 'title' } } };
    const result = resolveSolrConfig({ solrConfig: config });
    expect(result).toBe(config);
  });

  it('returns undefined when solrConfig is null', () => {
    expect(resolveSolrConfig({ solrConfig: null })).toBeUndefined();
  });
});

describe('transform', () => {
  // ─── Unknown endpoint passthrough ────────────────────────────────────────

  it('returns msg unchanged for unknown endpoint', () => {
    const msg = mockMsg('/unknown/path?q=*:*');
    const result = transform(msg);
    expect(result).toBe(msg); // same reference — not modified
  });

  it('does not write payload for unknown endpoint', () => {
    const msg = mockMsg('/unknown/path?q=*:*');
    transform(msg);
    expect(msg.get('payload')).toBeUndefined();
  });

  // ─── Select endpoint — body writeback ─────────────────────────────────────

  it('creates payload map when msg has no payload', () => {
    const msg = mockMsg('/solr/test/select?q=*:*');
    transform(msg);
    expect(msg.get('payload')).toBeDefined();
    expect(msg.get('payload').get('inlinedJsonBody')).toBeDefined();
  });

  it('writes query to inlinedJsonBody', () => {
    const msg = mockMsg('/solr/test/select?q=*:*');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    expect(body.has('query')).toBe(true);
  });

  it('returns msg (same reference) after processing', () => {
    const msg = mockMsg('/solr/test/select?q=*:*');
    const result = transform(msg);
    expect(result).toBe(msg);
  });

  // ─── rows/start params ────────────────────────────────────────────────────

  it('converts rows param to size', () => {
    const msg = mockMsg('/solr/test/select?q=*:*&rows=25');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    expect(body.get('size')).toBe(25);
  });

  it('converts start param to from', () => {
    const msg = mockMsg('/solr/test/select?q=*:*&start=10');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    expect(body.get('from')).toBe(10);
  });

  it('does not set size when rows is absent', () => {
    const msg = mockMsg('/solr/test/select?q=*:*');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    expect(body.has('size')).toBe(false);
  });

  it('does not set from when start is absent', () => {
    const msg = mockMsg('/solr/test/select?q=*:*');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    expect(body.has('from')).toBe(false);
  });

  // ─── match_all for *:* ────────────────────────────────────────────────────

  it('produces match_all query for *:*', () => {
    const msg = mockMsg('/solr/test/select?q=*:*');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    const query = body.get('query') as Map<string, any>;
    expect(query.has('match_all')).toBe(true);
  });

  // ─── match query for plain field:value (no fieldTypes in test env) ────────

  it('produces match query for field:value when no fieldTypes configured', () => {
    const msg = mockMsg('/solr/test/select?q=status:active');
    transform(msg);
    const body = msg.get('payload').get('inlinedJsonBody');
    const query = body.get('query') as Map<string, any>;
    // No fieldTypes in test env → falls back to match
    expect(query.has('match')).toBe(true);
  });
});
