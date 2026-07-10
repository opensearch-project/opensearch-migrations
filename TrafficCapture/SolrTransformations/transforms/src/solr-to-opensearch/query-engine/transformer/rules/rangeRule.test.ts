import { describe, it, expect } from 'vitest';
import { rangeRule } from './rangeRule';
import type { RangeNode } from '../../ast/nodes';

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

  // ─── Solr date-math translation (Fix 2) ────────────────────────────────────

  it('translates Solr date-math NOW-365DAYS to OpenSearch now-365d', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'review_date',
      lower: 'NOW-365DAYS',
      upper: 'NOW',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([
        [
          'range',
          new Map([['review_date', new Map([['gte', 'now-365d'], ['lte', 'now']])]]),
        ],
      ]),
    );
  });

  it('translates rounded date-math NOW/MONTH-6MONTHS to now/M-6M', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'd',
      lower: 'NOW/MONTH-6MONTHS',
      upper: 'NOW/MONTH',
      lowerInclusive: true,
      upperInclusive: false,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([
        [
          'range',
          new Map([['d', new Map([['gte', 'now/M-6M'], ['lt', 'now/M']])]]),
        ],
      ]),
    );
  });

  it('passes ISO-8601 timestamps through unchanged', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'd',
      lower: '2020-01-01T00:00:00Z',
      upper: '2022-12-31T23:59:59Z',
      lowerInclusive: true,
      upperInclusive: true,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([
        [
          'range',
          new Map([
            [
              'd',
              new Map([
                ['gte', '2020-01-01T00:00:00Z'],
                ['lte', '2022-12-31T23:59:59Z'],
              ]),
            ],
          ]),
        ],
      ]),
    );
  });

  it('combines date-math lower with ISO upper', () => {
    const node: RangeNode = {
      type: 'range',
      field: 'd',
      lower: 'NOW-7DAYS',
      upper: '2025-01-01T00:00:00Z',
      lowerInclusive: true,
      upperInclusive: false,
    };

    const result = rangeRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([
        [
          'range',
          new Map([
            [
              'd',
              new Map([
                ['gte', 'now-7d'],
                ['lt', '2025-01-01T00:00:00Z'],
              ]),
            ],
          ]),
        ],
      ]),
    );
  });
});
