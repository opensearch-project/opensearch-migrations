import { describe, it, expect } from 'vitest';
import { rangeRule } from './rangeRule';
import type { RangeNode, FieldNode } from '../../ast/nodes';

/** Stub transformChild — not used by rangeRule but required by signature. */
const stubTransformChild = () => new Map();

describe('rangeRule', () => {
  it('transforms inclusive range [10 TO 100] to gte/lte', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '10',
      upper: '100',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['range', new Map([['price', new Map([['gte', '10'], ['lte', '100']])]])]]),
    );
  });

  it('transforms exclusive range {10 TO 100} to gt/lt', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '10',
      upper: '100',
      lowerInclusive: false,
      upperInclusive: false,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['range', new Map([['price', new Map([['gt', '10'], ['lt', '100']])]])]]),
    );
  });

  it('transforms mixed range [10 TO 100} to gte/lt', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '10',
      upper: '100',
      lowerInclusive: true,
      upperInclusive: false,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['range', new Map([['price', new Map([['gte', '10'], ['lt', '100']])]])]]),
    );
  });

  it('transforms mixed range {10 TO 100] to gt/lte', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '10',
      upper: '100',
      lowerInclusive: false,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['range', new Map([['price', new Map([['gt', '10'], ['lte', '100']])]])]]),
    );
  });

  it('omits lower bound when unbounded [* TO 100]', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '*',
      upper: '100',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['range', new Map([['price', new Map([['lte', '100']])]])]]),
    );
  });

  it('omits upper bound when unbounded [10 TO *]', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '10',
      upper: '*',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['range', new Map([['price', new Map([['gte', '10']])]])]]),
    );
  });

  it('converts fully unbounded [* TO *] to exists query', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'price',
      lower: '*',
      upper: '*',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['exists', new Map([['field', 'price']])]]),
    );
  });

  it('preserves field name in output', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'created_at',
      lower: '2024-01-01',
      upper: '2024-12-31',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);
    const rangeMap = result.get('range') as Map<string, any>;

    expect(rangeMap.has('created_at')).toBe(true);
  });

  it('throws when called with wrong node type', () => {
    const wrongNode: FieldNode = { type: 'field', field: 'title', value: 'java' };
    expect(() => rangeRule(wrongNode, stubTransformChild)).toThrow(
      'rangeRule called with wrong node type: field',
    );
  });
});
