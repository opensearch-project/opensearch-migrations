import { describe, it, expect, vi } from 'vitest';
import { matchAllRule } from './matchAllRule';
import type { MatchAllNode } from '../../ast/nodes';

/** Stub transformChild — not used by matchAllRule but required by signature. */
const stubTransformChild = () => new Map();

describe('matchAllRule', () => {
  it('transforms matchAll to match_all query', () => {
    const node: MatchAllNode = { type: 'matchAll' };

    const result = matchAllRule(node, stubTransformChild);

    expect(result).toEqual(new Map([['match_all', new Map()]]));
  });

  it('produces match_all even when node contains extra properties', () => {
    const node = { type: 'matchAll', extra: 'ignored' } as MatchAllNode;

    const result = matchAllRule(node, stubTransformChild);

    expect(result.has('match_all')).toBe(true);
  });

  it('never recurses into children because matchAll is a leaf node', () => {
    const node: MatchAllNode = { type: 'matchAll' };
    const spy = vi.fn();

    matchAllRule(node, spy);

    expect(spy).not.toHaveBeenCalled();
  });
});
