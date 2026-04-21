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
    // Consecutive prefix operators are merged: +A -B → { and: [A], not: [B] }
    // This matches Solr semantics where +/- prefixed terms form an AND relationship
    expect(ast).toEqual({
      type: 'bool',
      and: [{ type: 'field', field: 'title', value: 'java' }],
      or: [],
      not: [{ type: 'field', field: 'status', value: 'draft' }],
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

  // ─── Prefix sequence merging ───────────────────────────────────────────
  // Consecutive +/- prefixed terms form an AND relationship in Solr.
  // This is correct parsing of Solr syntax, not a transformation.

  // Fallback path: single prefix still routes correctly through prefixExpr
  it('single +A falls through to prefixExpr (not prefixSequence)', () => {
    const { ast, errors } = parseSolrQuery('+title:java', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [{ type: 'field', field: 'title', value: 'java' }],
      or: [],
      not: [],
    });
  });

  it('single -A falls through to prefixExpr (not prefixSequence)', () => {
    const { ast, errors } = parseSolrQuery('-status:draft', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [{ type: 'field', field: 'status', value: 'draft' }],
    });
  });

  it('parses +A +B as merged must clauses', () => {
    const { ast, errors } = parseSolrQuery('+title:java +author:smith', emptyParams);
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

  it('parses -A -B as merged must_not clauses', () => {
    const { ast, errors } = parseSolrQuery('-status:draft -status:archived', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [
        { type: 'field', field: 'status', value: 'draft' },
        { type: 'field', field: 'status', value: 'archived' },
      ],
    });
  });

  it('parses +A +B +C as merged must clauses (three terms)', () => {
    const { ast, errors } = parseSolrQuery('+title:java +author:smith +year:2024', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'author', value: 'smith' },
        { type: 'field', field: 'year', value: '2024' },
      ],
      or: [],
      not: [],
    });
  });

  it('parses +A +B -C as mixed must/must_not', () => {
    const { ast, errors } = parseSolrQuery('+title:java +author:smith -status:draft', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
        { type: 'field', field: 'author', value: 'smith' },
      ],
      or: [],
      not: [
        { type: 'field', field: 'status', value: 'draft' },
      ],
    });
  });

  // Non-consecutive prefixes: bare term breaks the sequence — each run is separate
  it('non-consecutive +A B +C — bare term breaks prefix sequence into separate OR groups', () => {
    // +A and +C are separate prefixExpr nodes joined by implicit OR via orExpr
    // B is a bare term, also in the implicit OR
    const { ast, errors } = parseSolrQuery('+title:java author:smith +year:2024', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'bool', and: [{ type: 'field', field: 'title', value: 'java' }], or: [], not: [] },
        { type: 'field', field: 'author', value: 'smith' },
        { type: 'bool', and: [{ type: 'field', field: 'year', value: '2024' }], or: [], not: [] },
      ],
      not: [],
    });
  });

  // Precedence: prefixSequence binds tighter than AND/OR
  it('prefixSequence precedence — +A +B AND C treats +A +B as one unit', () => {
    // (+A +B) AND C — the prefix sequence is one operand of AND
    const { ast, errors } = parseSolrQuery('+title:java +author:smith AND category:tech', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'bool', and: [{ type: 'field', field: 'title', value: 'java' }, { type: 'field', field: 'author', value: 'smith' }], or: [], not: [] },
        { type: 'field', field: 'category', value: 'tech' },
      ],
      or: [],
      not: [],
    });
  });

  // Explicit OR overrides prefix merging — +A OR +B is two separate OR branches
  it('explicit OR between prefixed terms — +A OR +B is NOT merged', () => {
    const { ast, errors } = parseSolrQuery('+title:java OR +author:smith', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [
        { type: 'bool', and: [{ type: 'field', field: 'title', value: 'java' }], or: [], not: [] },
        { type: 'bool', and: [{ type: 'field', field: 'author', value: 'smith' }], or: [], not: [] },
      ],
      not: [],
    });
  });

  // ─── Grouped/nested expressions inside prefix operators ───────────────────
  // prefixWithType delegates to `primary`, and `primary` includes `group`,
  // so +(A OR B), -(A AND B), etc. are all valid.

  it('parses +(A OR B) — required group', () => {
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

  it('parses -(A OR B) — prohibited group', () => {
    const { ast, errors } = parseSolrQuery('-(status:draft OR status:archived)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [],
      or: [],
      not: [{
        type: 'group',
        child: {
          type: 'bool',
          and: [],
          or: [
            { type: 'field', field: 'status', value: 'draft' },
            { type: 'field', field: 'status', value: 'archived' },
          ],
          not: [],
        },
      }],
    });
  });

  it('parses +A +(B OR C) — required field and required group', () => {
    const { ast, errors } = parseSolrQuery('+title:java +(author:smith OR author:doe)', emptyParams);
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

  it('parses +A -(B AND C) — required field and prohibited nested AND group', () => {
    const { ast, errors } = parseSolrQuery('+title:java -(status:draft AND category:test)', emptyParams);
    expect(errors).toEqual([]);
    expect(ast).toEqual({
      type: 'bool',
      and: [
        { type: 'field', field: 'title', value: 'java' },
      ],
      or: [],
      not: [{
        type: 'group',
        child: {
          type: 'bool',
          and: [
            { type: 'field', field: 'status', value: 'draft' },
            { type: 'field', field: 'category', value: 'test' },
          ],
          or: [],
          not: [],
        },
      }],
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
