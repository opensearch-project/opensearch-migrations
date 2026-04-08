import { describe, it, expect } from 'vitest';
import { parseSolrQuery } from './parser';

const emptyParams = new Map<string, string>();
const paramsWithDf = (df: string) => new Map([['df', df]]);

describe('parseSolrQuery – BoolNode', () => {
  it('parses AND', () => {
    const { ast, errors } = parseSolrQuery('title:java AND author:smith', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'author', value: 'smith' },
      ],
      or: [],
      not: [],
    });
  });

  it('parses OR', () => {
    const { ast, errors } = parseSolrQuery('title:java OR title:python', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'title', value: 'python' },
      ],
      not: [],
    });
  });

  it('parses implicit OR (adjacency)', () => {
    const { ast, errors } = parseSolrQuery('title:java title:python', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'title', value: 'python' },
      ],
      not: [],
    });
  });

  it('parses NOT', () => {
    const { ast, errors } = parseSolrQuery('NOT status:draft', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [{ type: 'field', field: 'status', value: 'draft' }],
    });
  });


  it('respects precedence: AND binds tighter than OR', () => {
    const { ast, errors } = parseSolrQuery('title:java OR author:smith AND year:2024', emptyParams);
    expect(errors).toEqual([]);
    // title:java OR (author:smith AND year:2024)
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'field', field: 'title', value: 'java' },
        {
          type: 'bool',
          and: [
            { type: 'field', field: 'author', value: 'smith' },
            { type: 'field', field: 'year', value: '2024' },
          ],
          or: [],
          not: [],
        },
      ],
      not: [],
    });
  });

  it('respects precedence: NOT binds tighter than AND', () => {
    const { ast, errors } = parseSolrQuery('title:java AND NOT status:draft', emptyParams);
    expect(errors).toEqual([]);
    // title:java AND (NOT status:draft)
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'bool', and: [], or: [], not: [{ type: 'field', field: 'status', value: 'draft' }] },
      ],
      or: [],
      not: [],
    });
  });

  it('respects full precedence chain: OR < AND < NOT', () => {
    const { ast, errors } = parseSolrQuery('title:java OR author:smith AND NOT status:draft', emptyParams);
    expect(errors).toEqual([]);
    // title:java OR (author:smith AND (NOT status:draft))
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'field', field: 'title', value: 'java' },
        {
          type: 'bool',
          and: [
            { type: 'field', field: 'author', value: 'smith' },
            { type: 'bool', and: [], or: [], not: [{ type: 'field', field: 'status', value: 'draft' }] },
          ],
          or: [],
          not: [],
        },
      ],
      not: [],
    });
  });

  it('respects precedence: NOT binds tighter than OR', () => {
    const { ast, errors } = parseSolrQuery('title:java OR NOT status:draft', emptyParams);
    expect(errors).toEqual([]);
    // title:java OR (NOT status:draft)
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'bool', and: [], or: [], not: [{ type: 'field', field: 'status', value: 'draft' }] },
      ],
      not: [],
    });
  });

  it('parentheses override natural precedence', () => {
    const { ast, errors } = parseSolrQuery('(title:java OR author:smith) AND NOT status:draft', emptyParams);
    expect(errors).toEqual([]);
    // (title:java OR author:smith) AND (NOT status:draft)
    expect(ast).toEqual({
      type: 'bool',
      and: [
        {
          type: 'group',
          child: {
            type: 'bool', and: [], not: [],
            or: [
              { type: 'field', field: 'title', value: 'java' },
              { type: 'field', field: 'author', value: 'smith' },
            ],
          },
        },
        { type: 'bool', and: [], or: [], not: [{ type: 'field', field: 'status', value: 'draft' }] },
      ],
      or: [],
      not: [],
    });
  });

  // ─── Alternative boolean operators ─────────────────────────────────────

  it('parses && as AND', () => {
    const { ast, errors } = parseSolrQuery('title:java && author:smith', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'author', value: 'smith' },
      ],
      or: [],
      not: [],
    });
  });

  it('parses || as OR', () => {
    const { ast, errors } = parseSolrQuery('title:java || title:python', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'title', value: 'python' },
      ],
      not: [],
    });
  });

  it('parses ! as NOT', () => {
    const { ast, errors } = parseSolrQuery('!status:draft', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [{ type: 'field', field: 'status', value: 'draft' }],
    });
  });

  it('parses ! without whitespace', () => {
    const { ast, errors } = parseSolrQuery('title:java && !status:draft', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'bool', and: [], or: [], not: [{ type: 'field', field: 'status', value: 'draft' }] },
      ],
      or: [],
      not: [],
    });
  });

  // ─── Prefix operators (+/-) ────────────────────────────────────────────

  it('parses +term as required (must)', () => {
    const { ast, errors } = parseSolrQuery('+title:java', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [{ type: 'field', field: 'title', value: 'java' }],
      or: [],
      not: [],
    });
  });

  it('parses -term as prohibited (must_not)', () => {
    const { ast, errors } = parseSolrQuery('-status:draft', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [{ type: 'field', field: 'status', value: 'draft' }],
    });
  });

  it('parses mixed + and - prefixes', () => {
    const { ast, errors } = parseSolrQuery('+title:java -status:draft', emptyParams);
    expect(errors).toEqual([]);
    // Two adjacent terms → implicit OR
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'bool', and: [{ type: 'field', field: 'title', value: 'java' }], or: [], not: [] },
        { type: 'bool', and: [], or: [], not: [{ type: 'field', field: 'status', value: 'draft' }] },
      ],
      not: [],
    });
  });

  it('parses + and - with groups', () => {
    const { ast, errors } = parseSolrQuery('+(title:java OR title:python)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [{
        type: 'group',
        child: {
          type: 'bool',
          and: [],
          or: [
            { type: 'field', field: 'title', value: 'java' },
            { type: 'field', field: 'title', value: 'python' },
          ],
          not: [],
        },
      }],
      or: [],
      not: [],
    });
  });

  it('parses +bareterm (required bare term)', () => {
    const { ast, errors } = parseSolrQuery('+jakarta', paramsWithDf('content'));
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [{ type: 'bare', value: 'jakarta', isPhrase: false, defaultField: 'content' }],
      or: [],
      not: [],
    });
  });

  it('parses +jakarta lucene (required + optional bare terms)', () => {
    const { ast, errors } = parseSolrQuery('+jakarta lucene', paramsWithDf('content'));
    expect(errors).toEqual([]);
    // +jakarta is required, lucene is optional (implicit OR)
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'bool', and: [{ type: 'bare', value: 'jakarta', isPhrase: false, defaultField: 'content' }], or: [], not: [] },
        { type: 'bare', value: 'lucene', isPhrase: false, defaultField: 'content' },
      ],
      not: [],
    });
  });

  it('parses -bareterm (prohibited bare term)', () => {
    const { ast, errors } = parseSolrQuery('-draft', paramsWithDf('status'));
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [{ type: 'bare', value: 'draft', isPhrase: false, defaultField: 'status' }],
    });
  });
});


describe('parseSolrQuery – BoolNode with groups (precedence via AST nesting)', () => {
  it('parses A AND (B OR C) — group on right', () => {
    const { ast, errors } = parseSolrQuery('title:java AND (author:smith OR author:doe)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        {
          type: 'group',
          child: {
            type: 'bool',
            and: [],
            or: [
              { type: 'field', field: 'author', value: 'smith' },
              { type: 'field', field: 'author', value: 'doe' },
            ],
            not: [],
          },
        },
      ],
      or: [],
      not: [],
    });
  });

  it('parses (A OR B) AND (C OR D) — groups on both sides', () => {
    const { ast, errors } = parseSolrQuery('(title:java OR title:python) AND (author:smith OR author:doe)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        {
          type: 'group',
          child: {
            type: 'bool',
            and: [],
            or: [
              { type: 'field', field: 'title', value: 'java' },
              { type: 'field', field: 'title', value: 'python' },
            ],
            not: [],
          },
        },
        {
          type: 'group',
          child: {
            type: 'bool',
            and: [],
            or: [
              { type: 'field', field: 'author', value: 'smith' },
              { type: 'field', field: 'author', value: 'doe' },
            ],
            not: [],
          },
        },
      ],
      or: [],
      not: [],
    });
  });
});
