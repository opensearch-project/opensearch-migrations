import { describe, it, expect } from 'vitest';
import { parseSolrQuery, toParseError } from './parser';

const emptyParams = new Map<string, string>();
const paramsWithDf = (df: string) => new Map([['df', df]]);

describe('parseSolrQuery', () => {
  // ─── MatchAllNode ────────────────────────────────────────────────────────

  describe('MatchAllNode', () => {
    it('parses *:*', () => {
      const { ast, errors } = parseSolrQuery('*:*', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'matchAll' });
    });

    it('returns matchAll for empty query', () => {
      const { ast, errors } = parseSolrQuery('', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'matchAll' });
    });

    it('returns matchAll for whitespace-only query', () => {
      const { ast, errors } = parseSolrQuery('   ', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'matchAll' });
    });
  });

  // ─── FieldNode ───────────────────────────────────────────────────────────

  describe('FieldNode', () => {
    it('parses field:value', () => {
      const { ast, errors } = parseSolrQuery('title:java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'field', field: 'title', value: 'java' });
    });
  });

  // ─── BareNode ───────────────────────────────────────────────────────

  describe('BareNode', () => {
    it('parses bare term without df', () => {
      const { ast, errors } = parseSolrQuery('java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false });
    });

    it('parses bare term with df', () => {
      const { ast, errors } = parseSolrQuery('java', paramsWithDf('title'));
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false, defaultField: 'title' });
    });

    it('parses bare phrase without df', () => {
      const { ast, errors } = parseSolrQuery('"hello world"', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'hello world', isPhrase: true });
    });

    it('parses bare phrase with df', () => {
      const { ast, errors } = parseSolrQuery('"hello world"', paramsWithDf('content'));
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'hello world', isPhrase: true, defaultField: 'content' });
    });
  });

  // ─── PhraseNode ──────────────────────────────────────────────────────────

  describe('PhraseNode', () => {
    it('parses field:"phrase"', () => {
      const { ast, errors } = parseSolrQuery('title:"hello world"', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'phrase', text: 'hello world', field: 'title' });
    });
  });

  // ─── RangeNode ───────────────────────────────────────────────────────────

  describe('RangeNode', () => {
    it('parses inclusive range [low TO high]', () => {
      const { ast, errors } = parseSolrQuery('price:[10 TO 100]', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'range', field: 'price',
        lower: '10', upper: '100',
        lowerInclusive: true, upperInclusive: true,
      });
    });

    it('parses exclusive range {low TO high}', () => {
      const { ast, errors } = parseSolrQuery('price:{10 TO 100}', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'range', field: 'price',
        lower: '10', upper: '100',
        lowerInclusive: false, upperInclusive: false,
      });
    });

    it('parses mixed range [low TO high}', () => {
      const { ast, errors } = parseSolrQuery('price:[10 TO 100}', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'range', field: 'price',
        lower: '10', upper: '100',
        lowerInclusive: true, upperInclusive: false,
      });
    });

    it('parses mixed range {low TO high]', () => {
      const { ast, errors } = parseSolrQuery('price:{10 TO 100]', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'range', field: 'price',
        lower: '10', upper: '100',
        lowerInclusive: false, upperInclusive: true,
      });
    });

    it('parses unbounded range [* TO 100]', () => {
      const { ast, errors } = parseSolrQuery('price:[* TO 100]', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'range', field: 'price',
        lower: '*', upper: '100',
        lowerInclusive: true, upperInclusive: true,
      });
    });
  });

  // ─── BoolNode ────────────────────────────────────────────────────────────
  // BoolNode tests moved to boolNode.parser.test.ts

  // ─── GroupNode ───────────────────────────────────────────────────────────

  describe('GroupNode', () => {
    it('parses parenthesized group', () => {
      const { ast, errors } = parseSolrQuery('(title:java OR title:python)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
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
      });
    });

    it('parses adjacent groups with AND', () => {
      const { ast, errors } = parseSolrQuery('(title:java OR title:python) AND (status:active OR status:pending)', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          {
            type: 'group',
            child: {
              type: 'bool', and: [], not: [],
              or: [
                { type: 'field', field: 'title', value: 'java' },
                { type: 'field', field: 'title', value: 'python' },
              ],
            },
          },
          {
            type: 'group',
            child: {
              type: 'bool', and: [], not: [],
              or: [
                { type: 'field', field: 'status', value: 'active' },
                { type: 'field', field: 'status', value: 'pending' },
              ],
            },
          },
        ],
        or: [],
        not: [],
      });
    });

    it('parses nested groups', () => {
      // (title:java AND (author:smith OR author:doe))
      const { ast, errors } = parseSolrQuery('(title:java AND (author:smith OR author:doe))', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'group',
        child: {
          type: 'bool',
          and: [
            { type: 'field', field: 'title', value: 'java' },
            {
              type: 'group',
              child: {
                type: 'bool', and: [], not: [],
                or: [
                  { type: 'field', field: 'author', value: 'smith' },
                  { type: 'field', field: 'author', value: 'doe' },
                ],
              },
            },
          ],
          or: [],
          not: [],
        },
      });
    });
  });

  // ─── BoostNode ───────────────────────────────────────────────────────────

  describe('BoostNode', () => {
    it('parses field:value^N', () => {
      const { ast, errors } = parseSolrQuery('title:java^2', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: { type: 'field', field: 'title', value: 'java' },
        value: 2,
      });
    });

    it('parses decimal boost', () => {
      const { ast, errors } = parseSolrQuery('title:java^0.5', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: { type: 'field', field: 'title', value: 'java' },
        value: 0.5,
      });
    });

    it('parses boost on group', () => {
      const { ast, errors } = parseSolrQuery('(title:java OR author:smith)^3', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: {
          type: 'group',
          child: {
            type: 'bool', and: [], not: [],
            or: [
              { type: 'field', field: 'title', value: 'java' },
              { type: 'field', field: 'author', value: 'smith' },
            ],
          },
        },
        value: 3,
      });
    });

    it('resolves df through boost on bare value', () => {
      const { ast, errors } = parseSolrQuery('java^2', paramsWithDf('title'));
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: { type: 'bare', value: 'java', isPhrase: false, defaultField: 'title' },
        value: 2,
      });
    });

    it('parses boost on bare phrase', () => {
      const { ast, errors } = parseSolrQuery('"hello world"^2', paramsWithDf('content'));
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: { type: 'bare', value: 'hello world', isPhrase: true, defaultField: 'content' },
        value: 2,
      });
    });

    it('parses boost on match all *:*^2', () => {
      const { ast, errors } = parseSolrQuery('*:*^2', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'boost',
        child: { type: 'matchAll' },
        value: 2,
      });
    });

    it('fails to parse non-numeric boost value (^abc)', () => {
      const { ast, errors } = parseSolrQuery('title:java^abc', emptyParams);
      expect(ast).toBeNull();
      expect(errors).toHaveLength(1);
    });
  });

  // ─── Combined query ──────────────────────────────────────────────────────

  describe('combined query', () => {
    it('parses a realistic query with multiple node types', () => {
      const { ast, errors } = parseSolrQuery(
        'title:java^2 AND price:[10 TO 100] AND NOT "hello world"',
        paramsWithDf('content'),
      );
      expect(errors).toEqual([]);
      // title:java^2 AND price:[10 TO 100] AND (NOT "hello world")
      expect(ast).toEqual({
        type: 'bool',
        and: [
          {
            type: 'boost',
            child: { type: 'field', field: 'title', value: 'java' },
            value: 2,
          },
          {
            type: 'range', field: 'price',
            lower: '10', upper: '100',
            lowerInclusive: true, upperInclusive: true,
          },
          {
            type: 'bool', and: [], or: [], not: [
              { type: 'bare', value: 'hello world', isPhrase: true, defaultField: 'content' },
            ],
          },
        ],
        or: [],
        not: [],
      });
    });
  });

  // ─── Error handling ──────────────────────────────────────────────────────

  describe('error handling', () => {
    it.each([
      ['invalid query (unexpected closing paren)', 'title:java AND )'],
      ['unmatched opening parenthesis', '(title:java'],
      ['unclosed phrase', 'title:"hello world'],
    ])('returns null ast and error for %s', (_label, query) => {
      const { ast, errors } = parseSolrQuery(query, emptyParams);
      expect(ast).toBeNull();
      expect(errors).toHaveLength(1);
      expect(errors[0]).toEqual(
        expect.objectContaining({
          message: expect.any(String),
          position: expect.any(Number),
        }),
      );
    });
  });

  // ─── q.op parameter ──────────────────────────────────────────────────────

  describe('q.op=AND', () => {
    const paramsWithQopAnd = new Map([['q.op', 'AND']]);

    it('converts implicit OR to AND when q.op=AND', () => {
      const { ast, errors } = parseSolrQuery('title:java title:python', paramsWithQopAnd);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          { type: 'field', field: 'title', value: 'java' },
          { type: 'field', field: 'title', value: 'python' },
        ],
        or: [],
        not: [],
      });
    });

    it('leaves explicit OR unchanged when q.op=AND', () => {
      const { ast, errors } = parseSolrQuery('title:java OR title:python', paramsWithQopAnd);
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

    it('leaves explicit AND unchanged when q.op=AND', () => {
      const { ast, errors } = parseSolrQuery('title:java AND author:smith', paramsWithQopAnd);
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

    it('does not convert when q.op is not set', () => {
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

    it('does not leak implicit flag into the returned AST', () => {
      const { ast } = parseSolrQuery('title:java title:python', emptyParams);
      expect(ast).not.toBeNull();
      expect(ast).not.toHaveProperty('implicit');
    });

    it('converts nested implicit OR inside groups when q.op=AND', () => {
      const { ast, errors } = parseSolrQuery('(title:java title:python) AND author:smith', paramsWithQopAnd);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool',
        and: [
          {
            type: 'group',
            child: {
              type: 'bool',
              and: [
                { type: 'field', field: 'title', value: 'java' },
                { type: 'field', field: 'title', value: 'python' },
              ],
              or: [],
              not: [],
            },
          },
          { type: 'field', field: 'author', value: 'smith' },
        ],
        or: [],
        not: [],
      });
    });

    it('handles q.op=AND with mixed node types', () => {
      const { ast, errors } = parseSolrQuery(
        'title:java^2 "hello world" price:[10 TO 100]',
        new Map([['q.op', 'AND'], ['df', 'content']]),
      );
      expect(errors).toEqual([]);
      // All three terms joined by implicit adjacency → converted to AND
      expect(ast).not.toBeNull();
      expect(ast!.type).toBe('bool');
      if (ast!.type === 'bool') {
        expect(ast!.and).toHaveLength(3);
        expect(ast!.or).toHaveLength(0);
      }
    });
  });

  // ─── toParseError ────────────────────────────────────────────────────────

  describe('toParseError', () => {
    it('extracts message and position from peggy SyntaxError', () => {
      const err = { message: 'Expected ")"', location: { start: { offset: 5 } } };
      expect(toParseError(err)).toEqual({ message: 'Expected ")"', position: 5 });
    });

    it('falls back to default message when error has no message', () => {
      const err = { location: { start: { offset: 3 } } };
      expect(toParseError(err)).toEqual({ message: 'Parse error', position: 3 });
    });

    it('falls back to position 0 when error has no location', () => {
      const err = { message: 'Something broke' };
      expect(toParseError(err)).toEqual({ message: 'Something broke', position: 0 });
    });

    it('falls back to both defaults for a bare object', () => {
      expect(toParseError({})).toEqual({ message: 'Parse error', position: 0 });
    });

    it('handles null/undefined error', () => {
      expect(toParseError(null)).toEqual({ message: 'Parse error', position: 0 });
      expect(toParseError(undefined)).toEqual({ message: 'Parse error', position: 0 });
    });
  });
});
