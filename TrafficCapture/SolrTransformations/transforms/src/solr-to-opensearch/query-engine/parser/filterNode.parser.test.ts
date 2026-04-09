/**
 * Parser tests for FilterNode — Solr's filter(...) inline caching syntax.
 *
 * The filter() wrapper tells Solr to cache the inner clause and execute it
 * as a constant-score (non-scoring) clause.
 *
 * See: https://solr.apache.org/guide/solr/latest/query-guide/standard-query-parser.html
 */
import { describe, it, expect } from 'vitest';
import { parseSolrQuery } from './parser';

const emptyParams = new Map<string, string>();

describe('FilterNode parsing', () => {
  describe('simple filter expressions', () => {
    it('parses filter(field:value)', () => {
      const { ast, errors } = parseSolrQuery('filter(inStock:true)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: { type: 'field', field: 'inStock', value: 'true' },
      });
    });

    it('parses filter with range query', () => {
      const { ast, errors } = parseSolrQuery('filter(price:[10 TO 100])', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'range',
          field: 'price',
          lower: '10',
          upper: '100',
          lowerInclusive: true,
          upperInclusive: true,
        },
      });
    });

    it('parses filter with phrase query', () => {
      const { ast, errors } = parseSolrQuery('filter(title:"hello world")', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: { type: 'phrase', text: 'hello world', field: 'title' },
      });
    });

    it('parses filter with match all', () => {
      const { ast, errors } = parseSolrQuery('filter(*:*)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: { type: 'matchAll' },
      });
    });
  });

  describe('filter with boolean expressions', () => {
    it('parses filter with AND expression', () => {
      const { ast, errors } = parseSolrQuery('filter(status:active AND type:book)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'bool',
          and: [
            { type: 'field', field: 'status', value: 'active' },
            { type: 'field', field: 'type', value: 'book' },
          ],
          or: [],
          not: [],
        },
      });
    });

    it('parses filter with OR expression', () => {
      const { ast, errors } = parseSolrQuery('filter(a:1 OR b:2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'bool',
          and: [],
          or: [
            { type: 'field', field: 'a', value: '1' },
            { type: 'field', field: 'b', value: '2' },
          ],
          not: [],
        },
      });
    });

    it('parses filter with grouped expression', () => {
      const { ast, errors } = parseSolrQuery('filter((a:1 OR b:2))', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'group',
          child: {
            type: 'bool',
            and: [],
            or: [
              { type: 'field', field: 'a', value: '1' },
              { type: 'field', field: 'b', value: '2' },
            ],
            not: [],
          },
        },
      });
    });
  });

  describe('filter with boost', () => {
    it('parses filter with boost on inner clause', () => {
      const { ast, errors } = parseSolrQuery('filter(category:software^2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'boost',
          child: { type: 'field', field: 'category', value: 'software' },
          value: 2,
        },
      });
    });

    it('parses filter with boost on grouped inner clause', () => {
      const { ast, errors } = parseSolrQuery('filter((category:software)^2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'boost',
          child: {
            type: 'group',
            child: { type: 'field', field: 'category', value: 'software' },
          },
          value: 2,
        },
      });
    });

    it('parses filter with boost on filter wrapper', () => {
      const { ast, errors } = parseSolrQuery('filter(category:software)^2', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: {
          type: 'filter',
          child: { type: 'field', field: 'category', value: 'software' },
        },
        value: 2,
      });
    });

    it('parses filter with decimal boost on wrapper', () => {
      const { ast, errors } = parseSolrQuery('filter(inStock:true)^0.5', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: {
          type: 'filter',
          child: { type: 'field', field: 'inStock', value: 'true' },
        },
        value: 0.5,
      });
    });
  });

  describe('filter combined with other queries', () => {
    it('parses filter combined with AND', () => {
      const { ast, errors } = parseSolrQuery('title:java AND filter(inStock:true)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          { type: 'field', field: 'title', value: 'java' },
          {
            type: 'filter',
            child: { type: 'field', field: 'inStock', value: 'true' },
          },
        ],
        or: [],
        not: [],
      });
    });

    it('parses filter combined with OR', () => {
      const { ast, errors } = parseSolrQuery('title:java OR filter(inStock:true)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [],
        or: [
          { type: 'field', field: 'title', value: 'java' },
          {
            type: 'filter',
            child: { type: 'field', field: 'inStock', value: 'true' },
          },
        ],
        not: [],
      });
    });

    it('parses filter with prefix + (required)', () => {
      const { ast, errors } = parseSolrQuery('+filter(inStock:true)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          {
            type: 'filter',
            child: { type: 'field', field: 'inStock', value: 'true' },
          },
        ],
        or: [],
        not: [],
      });
    });

    it('parses filter with prefix - (prohibited)', () => {
      const { ast, errors } = parseSolrQuery('-filter(inStock:true)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [],
        or: [],
        not: [
          {
            type: 'filter',
            child: { type: 'field', field: 'inStock', value: 'true' },
          },
        ],
      });
    });
  });

  describe('multiple filter clauses', () => {
    it('parses multiple filter clauses with AND', () => {
      const { ast, errors } = parseSolrQuery('filter(a:1) AND filter(b:2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          { type: 'filter', child: { type: 'field', field: 'a', value: '1' } },
          { type: 'filter', child: { type: 'field', field: 'b', value: '2' } },
        ],
        or: [],
        not: [],
      });
    });

    it('parses union of filter clauses with OR', () => {
      const { ast, errors } = parseSolrQuery('filter(category:books) OR filter(category:electronics)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [],
        or: [
          { type: 'filter', child: { type: 'field', field: 'category', value: 'books' } },
          { type: 'filter', child: { type: 'field', field: 'category', value: 'electronics' } },
        ],
        not: [],
      });
    });
  });

  describe('nested filter expressions', () => {
    it('parses nested filter(filter(...))', () => {
      const { ast, errors } = parseSolrQuery('filter(filter(inStock:true))', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'filter',
          child: { type: 'field', field: 'inStock', value: 'true' },
        },
      });
    });

    it('parses nested filter with boolean inside', () => {
      const { ast, errors } = parseSolrQuery('filter(filter(a:1 AND b:2))', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'filter',
          child: {
            type: 'bool',
            and: [
              { type: 'field', field: 'a', value: '1' },
              { type: 'field', field: 'b', value: '2' },
            ],
            or: [],
            not: [],
          },
        },
      });
    });
  });

  describe('boost with boolean expressions', () => {
    it('parses filter with boosted AND expression', () => {
      const { ast, errors } = parseSolrQuery('filter((a:1 AND b:2)^2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'boost',
          child: {
            type: 'group',
            child: {
              type: 'bool',
              and: [
                { type: 'field', field: 'a', value: '1' },
                { type: 'field', field: 'b', value: '2' },
              ],
              or: [],
              not: [],
            },
          },
          value: 2,
        },
      });
    });

    it('parses filter with boosted OR expression', () => {
      const { ast, errors } = parseSolrQuery('filter((a:1 OR b:2)^1.5)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'boost',
          child: {
            type: 'group',
            child: {
              type: 'bool',
              and: [],
              or: [
                { type: 'field', field: 'a', value: '1' },
                { type: 'field', field: 'b', value: '2' },
              ],
              not: [],
            },
          },
          value: 1.5,
        },
      });
    });

    it('parses boosted filter containing boolean expression', () => {
      const { ast, errors } = parseSolrQuery('filter(a:1 AND b:2)^3', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: {
          type: 'filter',
          child: {
            type: 'bool',
            and: [
              { type: 'field', field: 'a', value: '1' },
              { type: 'field', field: 'b', value: '2' },
            ],
            or: [],
            not: [],
          },
        },
        value: 3,
      });
    });
  });

  describe('precedence validations', () => {
    it('parses AND binds tighter than OR inside filter', () => {
      // a OR b AND c should parse as a OR (b AND c)
      const { ast, errors } = parseSolrQuery('filter(a:1 OR b:2 AND c:3)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'bool',
          and: [],
          or: [
            { type: 'field', field: 'a', value: '1' },
            {
              type: 'bool',
              and: [
                { type: 'field', field: 'b', value: '2' },
                { type: 'field', field: 'c', value: '3' },
              ],
              or: [],
              not: [],
            },
          ],
          not: [],
        },
      });
    });

    it('parses NOT binds tightest inside filter', () => {
      // a AND NOT b should parse as a AND (NOT b)
      const { ast, errors } = parseSolrQuery('filter(a:1 AND NOT b:2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'bool',
          and: [
            { type: 'field', field: 'a', value: '1' },
            {
              type: 'bool',
              and: [],
              or: [],
              not: [{ type: 'field', field: 'b', value: '2' }],
            },
          ],
          or: [],
          not: [],
        },
      });
    });

    it('parses grouped expression overrides precedence inside filter', () => {
      // (a OR b) AND c should parse as (a OR b) AND c
      const { ast, errors } = parseSolrQuery('filter((a:1 OR b:2) AND c:3)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'filter',
        child: {
          type: 'bool',
          and: [
            {
              type: 'group',
              child: {
                type: 'bool',
                and: [],
                or: [
                  { type: 'field', field: 'a', value: '1' },
                  { type: 'field', field: 'b', value: '2' },
                ],
                not: [],
              },
            },
            { type: 'field', field: 'c', value: '3' },
          ],
          or: [],
          not: [],
        },
      });
    });

    it('parses filter in complex precedence context', () => {
      // a AND filter(b:1) OR c should parse as (a AND filter(b:1)) OR c
      const { ast, errors } = parseSolrQuery('a:1 AND filter(b:2) OR c:3', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [],
        or: [
          {
            type: 'bool',
            and: [
              { type: 'field', field: 'a', value: '1' },
              { type: 'filter', child: { type: 'field', field: 'b', value: '2' } },
            ],
            or: [],
            not: [],
          },
          { type: 'field', field: 'c', value: '3' },
        ],
        not: [],
      });
    });
  });

  describe('prefix operators with boost', () => {
    it('parses +filter(...)^boost', () => {
      const { ast, errors } = parseSolrQuery('+filter(inStock:true)^2', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          {
            type: 'boost',
            child: {
              type: 'filter',
              child: { type: 'field', field: 'inStock', value: 'true' },
            },
            value: 2,
          },
        ],
        or: [],
        not: [],
      });
    });

    it('parses -filter(...)^boost', () => {
      const { ast, errors } = parseSolrQuery('-filter(status:inactive)^1.5', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [],
        or: [],
        not: [
          {
            type: 'boost',
            child: {
              type: 'filter',
              child: { type: 'field', field: 'status', value: 'inactive' },
            },
            value: 1.5,
          },
        ],
      });
    });

    it('parses combined +filter and -filter with boosts', () => {
      const { ast, errors } = parseSolrQuery('+filter(a:1)^2 -filter(b:2)^0.5', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [],
        or: [
          {
            type: 'bool',
            and: [
              {
                type: 'boost',
                child: { type: 'filter', child: { type: 'field', field: 'a', value: '1' } },
                value: 2,
              },
            ],
            or: [],
            not: [],
          },
          {
            type: 'bool',
            and: [],
            or: [],
            not: [
              {
                type: 'boost',
                child: { type: 'filter', child: { type: 'field', field: 'b', value: '2' } },
                value: 0.5,
              },
            ],
          },
        ],
        not: [],
      });
    });

    it('parses +filter with boosted inner clause', () => {
      const { ast, errors } = parseSolrQuery('+filter(category:software^2)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          {
            type: 'filter',
            child: {
              type: 'boost',
              child: { type: 'field', field: 'category', value: 'software' },
              value: 2,
            },
          },
        ],
        or: [],
        not: [],
      });
    });
  });
});
