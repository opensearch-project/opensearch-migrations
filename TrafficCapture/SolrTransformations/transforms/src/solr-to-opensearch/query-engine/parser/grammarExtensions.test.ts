/**
 * Unit tests for grammar fixes:
 *
 *   1. `rangeVal` accepts ISO-8601 timestamps and Solr date-math
 *      (`NOW`, `NOW-365DAYS`, `NOW/MONTH-6MONTHS`, `2020-01-01T00:00:00Z`).
 *   2. `fieldExpr` accepts the `field:(a OR b OR c)` parenthesized
 *      value-group sugar and hoists the field onto every bare leaf
 *      inside the group.
 *
 * These are pure parser-level tests: we only assert on the AST shape, not
 * on downstream OpenSearch DSL output. Range/fieldExpr translation tests
 * live in `rangeRule.test.ts` and `astToOpenSearch.test.ts`.
 */

import { describe, it, expect } from 'vitest';
import { parseSolrQuery } from './parser';

const emptyParams = new Map<string, string>();

describe('grammar — rangeVal extensions (Fix 1)', () => {
  it('parses an ISO-8601 timestamp range', () => {
    const { ast, errors } = parseSolrQuery(
      'review_date:[2020-01-01T00:00:00Z TO 2022-12-31T23:59:59Z]',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      field: 'review_date',
      lower: '2020-01-01T00:00:00Z',
      upper: '2022-12-31T23:59:59Z',
      lowerInclusive: true,
      upperInclusive: true,
    });
  });

  it('parses an ISO-8601 timestamp with milliseconds', () => {
    const { ast, errors } = parseSolrQuery(
      'd:[2020-01-01T00:00:00.123Z TO 2020-01-02T00:00:00.456Z]',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      lower: '2020-01-01T00:00:00.123Z',
      upper: '2020-01-02T00:00:00.456Z',
    });
  });

  it('parses a NOW-365DAYS to NOW range', () => {
    const { ast, errors } = parseSolrQuery(
      'review_date:[NOW-365DAYS TO NOW]',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      field: 'review_date',
      lower: 'NOW-365DAYS',
      upper: 'NOW',
    });
  });

  it('parses a NOW/MONTH-6MONTHS rounding+offset bound', () => {
    const { ast, errors } = parseSolrQuery(
      'review_date:[NOW/MONTH-6MONTHS TO NOW/MONTH]',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      lower: 'NOW/MONTH-6MONTHS',
      upper: 'NOW/MONTH',
    });
  });

  it('parses a NOW+1HOUR bound and a wildcard upper', () => {
    const { ast, errors } = parseSolrQuery('d:[NOW+1HOUR TO *]', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      lower: 'NOW+1HOUR',
      upper: '*',
    });
  });

  it('still parses plain numeric ranges (regression)', () => {
    const { ast, errors } = parseSolrQuery('price:[10 TO 100]', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      field: 'price',
      lower: '10',
      upper: '100',
    });
  });

  it('still parses [* TO *] as matchAll-like exists (regression)', () => {
    const { ast, errors } = parseSolrQuery('price:[* TO *]', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'range',
      lower: '*',
      upper: '*',
    });
  });
});

describe('grammar — field:(group) hoisting (Fix 3)', () => {
  it('parses field:(a OR b) and hoists field onto each bare value', () => {
    const { ast, errors } = parseSolrQuery(
      'marketplace:(US OR UK)',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        or: [
          { type: 'field', field: 'marketplace', value: 'US' },
          { type: 'field', field: 'marketplace', value: 'UK' },
        ],
      },
    });
  });

  it('parses three-term group field:(a OR b OR c)', () => {
    const { ast, errors } = parseSolrQuery(
      'marketplace:(US OR UK OR JP)',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        or: [
          { type: 'field', field: 'marketplace', value: 'US' },
          { type: 'field', field: 'marketplace', value: 'UK' },
          { type: 'field', field: 'marketplace', value: 'JP' },
        ],
      },
    });
  });

  it('hoists field over an implicit-OR (whitespace-separated) group', () => {
    // `tag:(foo bar)` — implicit-OR sequence inside the group.
    const { ast, errors } = parseSolrQuery('tag:(foo bar)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        or: [
          { type: 'field', field: 'tag', value: 'foo' },
          { type: 'field', field: 'tag', value: 'bar' },
        ],
      },
    });
  });

  it('hoists field through a NOT operator inside the group', () => {
    const { ast, errors } = parseSolrQuery('tag:(NOT foo)', emptyParams);
    expect(errors).toEqual([]);
    // NOT foo → bool{ not: [bare(foo)] } — after hoisting becomes
    // bool{ not: [field{tag,foo}] }.
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        not: [{ type: 'field', field: 'tag', value: 'foo' }],
      },
    });
  });

  it('hoists field onto a bare phrase inside the group', () => {
    const { ast, errors } = parseSolrQuery(
      'tag:("hello world" OR baz)',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        or: [
          { type: 'phrase', field: 'tag', text: 'hello world' },
          { type: 'field', field: 'tag', value: 'baz' },
        ],
      },
    });
  });

  it('does NOT override an explicit nested field inside the group', () => {
    // Solr semantics: `tag:(foo OR other:bar)` keeps `other:bar` qualified
    // by `other`, not by `tag`.
    const { ast, errors } = parseSolrQuery(
      'tag:(foo OR other:bar)',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        or: [
          { type: 'field', field: 'tag', value: 'foo' },
          { type: 'field', field: 'other', value: 'bar' },
        ],
      },
    });
  });

  it('preserves boost on the outer paren group', () => {
    const { ast, errors } = parseSolrQuery(
      'marketplace:(US OR UK)^2.0',
      emptyParams,
    );
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'boost',
      value: 2,
      child: { type: 'group' },
    });
  });

  it('still parses an unfielded paren group (regression — existing `group` rule)', () => {
    const { ast, errors } = parseSolrQuery('(foo OR bar)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toMatchObject({
      type: 'group',
      child: {
        type: 'bool',
        or: [
          { type: 'bare', value: 'foo' },
          { type: 'bare', value: 'bar' },
        ],
      },
    });
  });
});
