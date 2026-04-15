import { describe, it, expect } from 'vitest';
import { runPipeline } from './pipeline';
import type { MicroTransform, TransformRegistry } from './pipeline';
import type { SolrEndpoint } from './context';

interface TestCtx {
  endpoint: SolrEndpoint;
  applied: string[];
}

function buildCtx(endpoint: SolrEndpoint = 'select'): TestCtx {
  return { endpoint, applied: [] };
}

function trackingTransform(name: string): MicroTransform<TestCtx> {
  return { name, apply: (ctx) => { ctx.applied.push(name); } };
}

describe('runPipeline', () => {
  it('runs global transforms for any endpoint', () => {
    const registry: TransformRegistry<TestCtx> = {
      global: [trackingTransform('global-1')],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    runPipeline(registry, ctx);
    expect(ctx.applied).toEqual(['global-1']);
  });

  it('runs endpoint-specific transforms after global', () => {
    const registry: TransformRegistry<TestCtx> = {
      global: [trackingTransform('global-1')],
      byEndpoint: {
        select: [trackingTransform('select-1'), trackingTransform('select-2')],
      },
    };
    const ctx = buildCtx('select');
    runPipeline(registry, ctx);
    expect(ctx.applied).toEqual(['global-1', 'select-1', 'select-2']);
  });

  it('skips endpoint group when no transforms registered for endpoint', () => {
    const registry: TransformRegistry<TestCtx> = {
      global: [trackingTransform('global-1')],
      byEndpoint: {
        select: [trackingTransform('select-1')],
      },
    };
    const ctx = buildCtx('update');
    runPipeline(registry, ctx);
    expect(ctx.applied).toEqual(['global-1']);
  });

  it('skips transforms when match returns false', () => {
    const guarded: MicroTransform<TestCtx> = {
      name: 'guarded',
      match: () => false,
      apply: (ctx) => { ctx.applied.push('guarded'); },
    };
    const registry: TransformRegistry<TestCtx> = {
      global: [guarded],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    runPipeline(registry, ctx);
    expect(ctx.applied).toEqual([]);
  });

  it('propagates errors from transforms', () => {
    const failing: MicroTransform<TestCtx> = {
      name: 'failing',
      apply: () => { throw new Error('transform failed'); },
    };
    const registry: TransformRegistry<TestCtx> = {
      global: [failing],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    expect(() => runPipeline(registry, ctx)).toThrow('transform failed');
  });

  it('stops pipeline on first error — subsequent transforms do not run', () => {
    const failing: MicroTransform<TestCtx> = {
      name: 'failing',
      apply: () => { throw new Error('boom'); },
    };
    const registry: TransformRegistry<TestCtx> = {
      global: [failing, trackingTransform('after-fail')],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    expect(() => runPipeline(registry, ctx)).toThrow('boom');
    expect(ctx.applied).toEqual([]);
  });

  it('runs transforms before failure and skips those after', () => {
    const failing: MicroTransform<TestCtx> = {
      name: 'failing',
      apply: () => { throw new Error('mid-pipeline error'); },
    };
    const registry: TransformRegistry<TestCtx> = {
      global: [trackingTransform('first'), failing, trackingTransform('third')],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    expect(() => runPipeline(registry, ctx)).toThrow('mid-pipeline error');
    expect(ctx.applied).toEqual(['first']);
  });

  it('does not run endpoint transforms if global transform throws', () => {
    const failing: MicroTransform<TestCtx> = {
      name: 'failing-global',
      apply: () => { throw new Error('global fail'); },
    };
    const registry: TransformRegistry<TestCtx> = {
      global: [failing],
      byEndpoint: {
        select: [trackingTransform('select-1')],
      },
    };
    const ctx = buildCtx('select');
    expect(() => runPipeline(registry, ctx)).toThrow('global fail');
    expect(ctx.applied).toEqual([]);
  });

  it('completes without error when global and byEndpoint are both empty', () => {
    const registry: TransformRegistry<TestCtx> = {
      global: [],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    runPipeline(registry, ctx);
    expect(ctx.applied).toEqual([]);
  });

  it('propagates errors thrown by match()', () => {
    const badMatch: MicroTransform<TestCtx> = {
      name: 'bad-match',
      match: () => { throw new Error('match exploded'); },
      apply: (ctx) => { ctx.applied.push('bad-match'); },
    };
    const registry: TransformRegistry<TestCtx> = {
      global: [trackingTransform('before'), badMatch, trackingTransform('after')],
      byEndpoint: {},
    };
    const ctx = buildCtx();
    expect(() => runPipeline(registry, ctx)).toThrow('match exploded');
    expect(ctx.applied).toEqual(['before']);
  });
});
