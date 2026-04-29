import { describe, it, expect } from 'vitest';
import { handleJsonDocsArray, handleBatchAdd, handleBatchDelete, handleMixedCommands } from './update-batch';
import type { RequestContext, JavaMap } from '../context';

function buildCtx(commitParam?: string): RequestContext {
  const params = new URLSearchParams();
  if (commitParam) params.set('commit', commitParam);
  const msg = new Map<string, any>([
    ['URI', '/solr/mycore/update'],
    ['method', 'POST'],
    ['headers', new Map<string, any>([['Content-Type', 'application/json']])],
  ]) as unknown as JavaMap;
  return {
    msg,
    endpoint: 'update',
    collection: 'mycore',
    params,
    body: new Map() as unknown as JavaMap,
  } as RequestContext;
}

function doc(id: string, title: string): Map<string, any> {
  return new Map([['id', id], ['title', title]]);
}

function addWrap(d: Map<string, any>): Map<string, any> {
  return new Map([['doc', d]]);
}

describe('handleJsonDocsArray', () => {
  it('converts array of docs to _bulk request', () => {
    const ctx = buildCtx();
    handleJsonDocsArray(ctx, [doc('1', 'a'), doc('2', 'b')]);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
    expect(ctx.msg.get('method')).toBe('POST');
  });

  it('sets refresh=true when commit=true', () => {
    const ctx = buildCtx('true');
    handleJsonDocsArray(ctx, [doc('1', 'a')]);
    expect(ctx.msg.get('URI')).toContain('refresh=true');
  });

  it('throws on empty array', () => {
    const ctx = buildCtx();
    expect(() => handleJsonDocsArray(ctx, [])).toThrow('array is empty');
  });

  it('throws on doc missing id', () => {
    const ctx = buildCtx();
    expect(() => handleJsonDocsArray(ctx, [new Map([['title', 'no id']])])).toThrow('"id" field');
  });

  it('throws on non-object element', () => {
    const ctx = buildCtx();
    expect(() => handleJsonDocsArray(ctx, ['not an object'])).toThrow('JSON object');
  });
});

describe('handleBatchAdd', () => {
  it('converts array of add commands to _bulk', () => {
    const ctx = buildCtx();
    handleBatchAdd(ctx, [addWrap(doc('1', 'a')), addWrap(doc('2', 'b'))]);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
  });

  it('throws on empty array', () => {
    const ctx = buildCtx();
    expect(() => handleBatchAdd(ctx, [])).toThrow('array is empty');
  });

  it('throws on doc missing id', () => {
    const ctx = buildCtx();
    expect(() => handleBatchAdd(ctx, [addWrap(new Map([['title', 'x']]))])).toThrow('"id" field');
  });

  it('throws on add with boost', () => {
    const ctx = buildCtx();
    const item = new Map<string, any>([['doc', doc('1', 'a')], ['boost', 2]]);
    expect(() => handleBatchAdd(ctx, [item])).toThrow('boost');
  });

  it('throws on add with overwrite=false', () => {
    const ctx = buildCtx();
    const item = new Map<string, any>([['doc', doc('1', 'a')], ['overwrite', false]]);
    expect(() => handleBatchAdd(ctx, [item])).toThrow('overwrite');
  });

  it('throws on item missing doc field', () => {
    const ctx = buildCtx();
    expect(() => handleBatchAdd(ctx, [new Map([['id', '1']])])).toThrow('"doc" field');
  });
});

describe('handleBatchDelete', () => {
  it('converts array of IDs to _bulk delete', () => {
    const ctx = buildCtx();
    handleBatchDelete(ctx, ['1', '2', '3']);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
  });

  it('sets refresh=true when commit=true', () => {
    const ctx = buildCtx('true');
    handleBatchDelete(ctx, ['1']);
    expect(ctx.msg.get('URI')).toContain('refresh=true');
  });

  it('throws on empty array', () => {
    const ctx = buildCtx();
    expect(() => handleBatchDelete(ctx, [])).toThrow('array is empty');
  });

  it('throws on empty string ID', () => {
    const ctx = buildCtx();
    expect(() => handleBatchDelete(ctx, ['1', '', '3'])).toThrow('non-empty');
  });

  it('handles numeric IDs', () => {
    const ctx = buildCtx();
    handleBatchDelete(ctx, [1, 2, 3]);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
  });
});

describe('handleMixedCommands', () => {
  it('flattens add + delete into one _bulk request', () => {
    const ctx = buildCtx();
    ctx.body = new Map<string, any>([
      ['add', [addWrap(doc('1', 'new'))]],
      ['delete', ['2', '3']],
    ]) as unknown as JavaMap;
    handleMixedCommands(ctx);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
  });

  it('sets refresh=true when commit command is present', () => {
    const ctx = buildCtx();
    ctx.body = new Map<string, any>([
      ['add', [addWrap(doc('1', 'x'))]],
      ['commit', new Map()],
    ]) as unknown as JavaMap;
    handleMixedCommands(ctx);
    expect(ctx.msg.get('URI')).toContain('refresh=true');
  });

  it('handles single add (non-array) in mixed context', () => {
    const ctx = buildCtx();
    ctx.body = new Map<string, any>([
      ['add', addWrap(doc('1', 'x'))],
      ['delete', ['2']],
    ]) as unknown as JavaMap;
    handleMixedCommands(ctx);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
  });

  it('handles single delete-by-id (non-array) in mixed context', () => {
    const ctx = buildCtx();
    ctx.body = new Map<string, any>([
      ['add', [addWrap(doc('1', 'x'))]],
      ['delete', new Map([['id', '2']])],
    ]) as unknown as JavaMap;
    handleMixedCommands(ctx);
    expect(ctx.msg.get('URI')).toContain('/mycore/_bulk');
  });

  it('throws when only commit present (no add/delete)', () => {
    const ctx = buildCtx();
    ctx.body = new Map<string, any>([['commit', new Map()]]) as unknown as JavaMap;
    // commit-only should be handled by the commit handler, not mixed
    expect(() => handleMixedCommands(ctx)).toThrow('commit-only');
  });

  it('throws when no actionable commands found', () => {
    const ctx = buildCtx();
    ctx.body = new Map<string, any>([['optimize', new Map()]]) as unknown as JavaMap;
    expect(() => handleMixedCommands(ctx)).toThrow('no actionable');
  });
});
