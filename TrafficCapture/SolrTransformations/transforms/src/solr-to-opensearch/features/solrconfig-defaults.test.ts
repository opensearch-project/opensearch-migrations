import { describe, it, expect } from 'vitest';
import { request } from './solrconfig-defaults';
import { buildRequestContext, type RequestContext, type JavaMap } from '../context';
import { runPipeline } from '../pipeline';
import { requestRegistry } from '../registry';

function makeCtx(endpoint: string, params: Record<string, string>,
    solrConfig?: RequestContext['solrConfig']): RequestContext {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) p.set(k, v);
  return {
    msg: new Map(),
    endpoint: endpoint as any,
    collection: 'test',
    params: p,
    body: new Map(),
    solrConfig,
  };
}

describe('solrconfig-defaults', () => {
  it('is a no-op when solrConfig is not set', () => {
    const ctx = makeCtx('select', { q: 'laptop' });
    expect(request.match!(ctx)).toBe(false);
  });

  it('applies defaults when param is missing', () => {
    const ctx = makeCtx('select', { q: 'laptop' },
      { '/select': { defaults: { df: 'title', rows: '20' } } });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('title');
    expect(ctx.params.get('rows')).toBe('20');
  });

  it('does not override existing params with defaults', () => {
    const ctx = makeCtx('select', { q: 'laptop', df: 'description', rows: '5' },
      { '/select': { defaults: { df: 'title', rows: '20' } } });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('description');
    expect(ctx.params.get('rows')).toBe('5');
  });

  it('invariants always override request params', () => {
    const ctx = makeCtx('select', { q: 'laptop', 'facet.field': 'brand' },
      { '/select': { invariants: { 'facet.field': 'cat' } } });
    request.apply(ctx);
    expect(ctx.params.get('facet.field')).toBe('cat');
  });

  it('applies both defaults and invariants', () => {
    const ctx = makeCtx('select', { q: 'laptop', wt: 'xml' }, {
      '/select': {
        defaults: { df: 'title' },
        invariants: { wt: 'json' },
      },
    });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('title');
    expect(ctx.params.get('wt')).toBe('json'); // invariant overrides
  });

  it('skips when endpoint has no handler config', () => {
    const ctx = makeCtx('update', { q: 'laptop' },
      { '/select': { defaults: { df: 'title' } } });
    request.apply(ctx);
    expect(ctx.params.has('df')).toBe(false);
  });

  it('handles empty defaults and invariants', () => {
    const ctx = makeCtx('select', { q: 'laptop' }, { '/select': {} });
    request.apply(ctx);
    expect(ctx.params.get('q')).toBe('laptop');
  });

  it('handles multiple handlers — matches correct endpoint', () => {
    const ctx = makeCtx('select', { q: 'test' }, {
      '/select': { defaults: { df: 'title' } },
      '/query': { defaults: { df: 'content' } },
    });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('title');
  });

  it('invariants override even when param matches default', () => {
    const ctx = makeCtx('select', { q: 'test' }, {
      '/select': {
        defaults: { df: 'title' },
        invariants: { df: 'forced_field' },
      },
    });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('forced_field');
  });

  it('appends adds alongside existing param values', () => {
    const ctx = makeCtx('select', { q: '*:*', fq: 'category:books' },
      { '/select': { appends: { fq: 'inStock:true' } } });
    request.apply(ctx);
    expect(ctx.params.getAll('fq')).toEqual(['category:books', 'inStock:true']);
  });

  it('appends adds param when not present in request', () => {
    const ctx = makeCtx('select', { q: '*:*' },
      { '/select': { appends: { fq: 'inStock:true' } } });
    request.apply(ctx);
    expect(ctx.params.getAll('fq')).toEqual(['inStock:true']);
  });

  it('defaults + appends + invariants all applied together', () => {
    const ctx = makeCtx('select', { q: 'test', wt: 'xml' }, {
      '/select': {
        defaults: { df: 'title', rows: '10' },
        appends: { fq: 'inStock:true' },
        invariants: { wt: 'json' },
      },
    });
    request.apply(ctx);
    expect(ctx.params.get('df')).toBe('title');
    expect(ctx.params.get('rows')).toBe('10');
    expect(ctx.params.getAll('fq')).toEqual(['inStock:true']);
    expect(ctx.params.get('wt')).toBe('json');
  });
});

function makeRequestMsg(uri: string): JavaMap {
  const map = new Map<string, any>();
  map.set('URI', uri);
  return map as unknown as JavaMap;
}

describe('solrconfig-defaults pipeline integration', () => {
  it('pipeline applies defaults when ctx.solrConfig is set', () => {
    const msg = makeRequestMsg('/solr/testcollection/select?q=laptop&wt=json');
    const ctx = buildRequestContext(msg);
    ctx.solrConfig = { '/select': { defaults: { df: 'content', rows: '15' } } };

    runPipeline(requestRegistry, ctx);

    expect(ctx.params.get('df')).toBe('content');
    expect(ctx.params.get('rows')).toBe('15');
  });

  it('pipeline skips solrconfig-defaults when ctx.solrConfig is undefined', () => {
    const msg = makeRequestMsg('/solr/testcollection/select?q=laptop&wt=json');
    const ctx = buildRequestContext(msg);

    runPipeline(requestRegistry, ctx);

    expect(ctx.params.has('df')).toBe(false);
  });

  it('pipeline applies solrConfig before other transforms', () => {
    const msg = makeRequestMsg('/solr/testcollection/select?q=laptop');
    const ctx = buildRequestContext(msg);
    ctx.solrConfig = { '/select': { defaults: { df: 'title' } } };

    runPipeline(requestRegistry, ctx);

    // df=title should be set by solrconfig-defaults (runs first),
    // then downstream transforms (query-q etc.) see it
    expect(ctx.params.get('df')).toBe('title');
  });
});
