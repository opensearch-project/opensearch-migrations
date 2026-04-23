import { describe, it, expect } from 'vitest';
import { parseSolrQuery, toParseError } from './parser';
import { transformNode } from '../transformer/astToOpenSearch';

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

  // ─── LocalParamsNode ──────────────────────────────────────────────────────

  describe('LocalParamsNode', () => {
    it('parses type short form: {!dismax}java', () => {
      const { ast, errors } = parseSolrQuery('{!dismax}java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: [{ key: 'type', value: 'dismax', deref: false }],
        body: { type: 'bare', value: 'java', isPhrase: false },
      });
    });

    it('parses key=value pairs with quoted value', () => {
      const { ast, errors } = parseSolrQuery("{!edismax qf='title author'}java", emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: [
          { key: 'type', value: 'edismax', deref: false },
          { key: 'qf', value: 'title author', deref: false },
        ],
        body: { type: 'bare', value: 'java', isPhrase: false },
      });
    });

    it('parses empty local params: {!}java', () => {
      const { ast, errors } = parseSolrQuery('{!}java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: [],
        body: { type: 'bare', value: 'java', isPhrase: false },
      });
    });

    it('parses v key and re-parses its value as body', () => {
      const { ast, errors } = parseSolrQuery("{!dismax qf=title v='solr rocks'}", emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: expect.arrayContaining([
          { key: 'type', value: 'dismax', deref: false },
          { key: 'qf', value: 'title', deref: false },
          { key: 'v', value: 'solr rocks', deref: false },
        ]),
      });
      // v key body is re-parsed: "solr rocks" → two bare terms joined by implicit OR
      expect(ast).not.toBeNull();
      expect(ast?.type).toBe('localParams');
      if (ast?.type === 'localParams') {
        expect(ast.body).not.toBeNull();
        expect(ast.body?.type).toBe('bool');
      }
    });

    it('parses dereference value: {!dismax v=$qq}', () => {
      const { ast, errors } = parseSolrQuery('{!dismax v=$qq}', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: [
          { key: 'type', value: 'dismax', deref: false },
          { key: 'v', value: 'qq', deref: true },
        ],
        body: null,
      });
    });

    it('returns parse error for missing closing }', () => {
      const { ast, errors } = parseSolrQuery('{!dismax', emptyParams);
      expect(ast).toBeNull();
      expect(errors).toHaveLength(1);
    });

    it('returns parse error for unterminated quoted value', () => {
      // The grammar treats the unterminated quote as consuming everything to end of input
      const { ast, errors } = parseSolrQuery("{!dismax qf='unterminated", emptyParams);
      expect(ast).toBeNull();
      expect(errors).toHaveLength(1);
    });

    it('handles backslash escapes in single-quoted values', () => {
      // Use a key other than 'v' so the value isn't re-parsed as a query.
      // The grammar should unescape \' inside single-quoted values.
      const BS = String.fromCodePoint(92);  // backslash
      const SQ = String.fromCodePoint(39);  // single quote
      // Input: {!dismax qf='it\'s'}java — qf value contains an escaped quote
      const input = '{' + '!dismax qf=' + SQ + 'it' + BS + SQ + 's' + SQ + '}java';
      const { ast, errors } = parseSolrQuery(input, emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: expect.arrayContaining([
          expect.objectContaining({ key: 'qf', value: "it's" }),
        ]),
      });
    });

    it('backward compat: title:java produces no LocalParamsNode', () => {
      const { ast, errors } = parseSolrQuery('title:java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast?.type).not.toBe('localParams');
    });

    it('resolves default fields through LocalParamsNode body', () => {
      const { ast, errors } = parseSolrQuery('{!dismax}java', paramsWithDf('title'));
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        body: { type: 'bare', value: 'java', isPhrase: false, defaultField: 'title' },
      });
    });

    it('applies q.op=AND through LocalParamsNode body', () => {
      const params = new Map([['q.op', 'AND']]);
      const { ast, errors } = parseSolrQuery('{!dismax}title:java title:python', params);
      expect(errors).toEqual([]);
      expect(ast?.type).toBe('localParams');
      if (ast?.type === 'localParams' && ast.body?.type === 'bool') {
        expect(ast.body.and).toHaveLength(2);
        expect(ast.body.or).toHaveLength(0);
      }
    });

    it('v key body override ignores trailing text', () => {
      const { ast, errors } = parseSolrQuery("{!dismax v='solr rocks'}trailing", emptyParams);
      expect(errors).toEqual([]);
      // The body should come from v='solr rocks', not from 'trailing'
      expect(ast?.type).toBe('localParams');
      if (ast?.type === 'localParams') {
        expect(ast.body).not.toBeNull();
        // "solr rocks" parses to two bare terms
        expect(ast.body?.type).toBe('bool');
      }
    });

    it('parses double-quoted values', () => {
      const { ast, errors } = parseSolrQuery('{!dismax qf="title author"}java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: expect.arrayContaining([
          { key: 'qf', value: 'title author', deref: false },
        ]),
      });
    });

    it('parses local params with no body', () => {
      const { ast, errors } = parseSolrQuery('{!dismax}', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: [{ key: 'type', value: 'dismax', deref: false }],
        body: null,
      });
    });

    it('parses explicit type=dismax key-value pair', () => {
      const { ast, errors } = parseSolrQuery('{!type=dismax qf=title}java', emptyParams);
      expect(errors).toEqual([]);
      expect(ast).toMatchObject({
        type: 'localParams',
        params: [
          { key: 'type', value: 'dismax', deref: false },
          { key: 'qf', value: 'title', deref: false },
        ],
      });
    });

    it('returns error when v key value fails to re-parse', () => {
      // v='title:java AND )' is invalid Solr syntax — the re-parse should fail
      const { ast, errors } = parseSolrQuery("{!dismax v='title:java AND )'}", emptyParams);
      expect(ast).toBeNull();
      expect(errors).toHaveLength(1);
      expect(errors[0].message).toBeDefined();
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
      expect(ast?.type).toBe('bool');
      if (ast?.type === 'bool') {
        expect(ast.and).toHaveLength(3);
        expect(ast.or).toHaveLength(0);
      }
    });
  });

  // ─── defType + qf (edismax/dismax) ──────────────────────────────────────

  describe('defType + qf', () => {
    it('stamps queryFields on BareNode when defType=edismax and qf is set', () => {
      const params = new Map([['defType', 'edismax'], ['qf', 'title^2 body']]);
      const { ast, errors } = parseSolrQuery('java', params);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false, queryFields: ['title^2', 'body'] });
    });

    it('stamps queryFields on BareNode when defType=dismax and qf is set', () => {
      const params = new Map([['defType', 'dismax'], ['qf', 'title body']]);
      const { ast, errors } = parseSolrQuery('java', params);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false, queryFields: ['title', 'body'] });
    });

    it('queryFields takes precedence over df on BareNode', () => {
      const params = new Map([['defType', 'edismax'], ['qf', 'title body'], ['df', 'content']]);
      const { ast, errors } = parseSolrQuery('java', params);
      expect(errors).toEqual([]);
      // queryFields set, defaultField should not be set
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false, queryFields: ['title', 'body'] });
    });

    it('does not stamp queryFields when defType is standard (no defType param)', () => {
      const params = new Map([['qf', 'title body']]);
      const { ast, errors } = parseSolrQuery('java', params);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false });
    });

    it('does not stamp queryFields when qf is absent', () => {
      const params = new Map([['defType', 'edismax']]);
      const { ast, errors } = parseSolrQuery('java', params);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'bare', value: 'java', isPhrase: false });
    });

    it('stamps queryFields on all BareNodes in a bool query', () => {
      const params = new Map([['defType', 'edismax'], ['qf', 'title body']]);
      const { ast, errors } = parseSolrQuery('java python', params);
      expect(errors).toEqual([]);
      expect(ast).toEqual({
        type: 'bool', and: [], not: [],
        or: [
          { type: 'bare', value: 'java', isPhrase: false, queryFields: ['title', 'body'] },
          { type: 'bare', value: 'python', isPhrase: false, queryFields: ['title', 'body'] },
        ],
      });
    });

    it('does not stamp queryFields on FieldNode (explicit field syntax)', () => {
      const params = new Map([['defType', 'edismax'], ['qf', 'title body']]);
      const { ast, errors } = parseSolrQuery('title:java', params);
      expect(errors).toEqual([]);
      expect(ast).toEqual({ type: 'field', field: 'title', value: 'java' });
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

  // ─── FuncNode ──────────────────────────────────────────────────────────────

  describe('FuncNode — grammar parsing via {!func} local params', () => {
    it('parses {!func}sum(popularity, 1) → FuncNode with field ref and numeric args', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum(popularity, 1)', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('sum');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'popularity' },
        { kind: 'numeric', value: 1 },
      ]);
    });

    it('parses {!func}recip(ms(NOW, date), 3.16e-11, 1, 1) → nested FuncNode in args', () => {
      const { ast, errors } = parseSolrQuery('{!func}recip(ms(NOW, date), 3.16e-11, 1, 1)', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('recip');
      expect(lp.body.args).toHaveLength(4);
      // First arg is a nested function call (grammar returns FuncNode directly)
      expect(lp.body.args[0]).toEqual({
        type: 'func',
        name: 'ms',
        args: [
          { kind: 'field', name: 'NOW' },
          { kind: 'field', name: 'date' },
        ],
      });
      expect(lp.body.args[1]).toEqual({ kind: 'numeric', value: 3.16e-11 });
      expect(lp.body.args[2]).toEqual({ kind: 'numeric', value: 1 });
      expect(lp.body.args[3]).toEqual({ kind: 'numeric', value: 1 });
    });

    it('parses {!func}now() → FuncNode with empty args', () => {
      const { ast, errors } = parseSolrQuery('{!func}now()', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('now');
      expect(lp.body.args).toEqual([]);
    });

    it('parses {!func}sum(popularity, -1) → numeric(-1) arg', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum(popularity, -1)', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'popularity' },
        { kind: 'numeric', value: -1 },
      ]);
    });

    it('parses {!func}recip(age, 3.16e-11, 1, 1) → numeric(3.16e-11) arg', () => {
      const { ast, errors } = parseSolrQuery('{!func}recip(age, 3.16e-11, 1, 1)', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.name).toBe('recip');
      expect(lp.body.args[0]).toEqual({ kind: 'field', name: 'age' });
      expect(lp.body.args[1]).toEqual({ kind: 'numeric', value: 3.16e-11 });
    });

    it('parses {!func}div(price, 0.5) → numeric(0.5) arg', () => {
      const { ast, errors } = parseSolrQuery('{!func}div(price, 0.5)', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.name).toBe('div');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'price' },
        { kind: 'numeric', value: 0.5 },
      ]);
    });

    it("parses {!func}strdist('hello', name, edit) → string('hello') arg", () => {
      const { ast, errors } = parseSolrQuery("{!func}strdist('hello', name, edit)", emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.name).toBe('strdist');
      expect(lp.body.args).toEqual([
        { kind: 'string', value: 'hello' },
        { kind: 'field', name: 'name' },
        { kind: 'field', name: 'edit' },
      ]);
    });

    it(String.raw`parses {!func}strdist('it\'s', name, edit) → string("it's") arg (escape handling)`, () => {
      const input = String.raw`{!func}strdist('it\'s', name, edit)`;
      const { ast, errors } = parseSolrQuery(input, emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.name).toBe('strdist');
      expect(lp.body.args[0]).toEqual({ kind: 'string', value: "it's" });
    });

    it('parses {!func}sum( popularity , 1 ) → whitespace tolerance', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum( popularity , 1 )', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('sum');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'popularity' },
        { kind: 'numeric', value: 1 },
      ]);
    });

    it('parses {!func}abs(price) → single field ref arg', () => {
      const { ast, errors } = parseSolrQuery('{!func}abs(price)', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('abs');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'price' },
      ]);
    });

    it('parses {!func}sum(product(a, b), div(c, d)) → deeply nested functions', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum(product(a, b), div(c, d))', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('sum');
      expect(lp.body.args).toEqual([
        {
          type: 'func',
          name: 'product',
          args: [
            { kind: 'field', name: 'a' },
            { kind: 'field', name: 'b' },
          ],
        },
        {
          type: 'func',
          name: 'div',
          args: [
            { kind: 'field', name: 'c' },
            { kind: 'field', name: 'd' },
          ],
        },
      ]);
    });
  });

  describe('FuncNode — parser routing and error cases', () => {
    it("parses {!func v='sum(popularity, 1)'} → LocalParamsNode with FuncNode body from v key", () => {
      const { ast, errors } = parseSolrQuery("{!func v='sum(popularity, 1)'}", emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body).not.toBeNull();
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('sum');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'popularity' },
        { kind: 'numeric', value: 1 },
      ]);
    });

    it('parses {!func v=$qq} → LocalParamsNode with body: null (dereference)', () => {
      const { ast, errors } = parseSolrQuery('{!func v=$qq}', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.params).toEqual(
        expect.arrayContaining([
          { key: 'type', value: 'func', deref: false },
          { key: 'v', value: 'qq', deref: true },
        ]),
      );
      expect(lp.body).toBeNull();
    });

    it('parses {!func}not_a_function → parse error (no ( after identifier)', () => {
      const { ast, errors } = parseSolrQuery('{!func}not_a_function', emptyParams);
      expect(ast).toBeNull();
      expect(errors.length).toBeGreaterThanOrEqual(1);
    });

    it('parses {!func}sum(popularity, 1 → parse error (missing closing paren)', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum(popularity, 1', emptyParams);
      expect(ast).toBeNull();
      expect(errors.length).toBeGreaterThanOrEqual(1);
    });

    it('parses {!func} → LocalParamsNode with body: null (no body)', () => {
      const { ast, errors } = parseSolrQuery('{!func}', emptyParams);
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body).toBeNull();
    });
  });

  describe('FuncNode — AST walker and transformer integration', () => {
    it('resolveDefaultFields handles FuncNode body (df=title)', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum(popularity, 1)', new Map([['df', 'title']]));
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('sum');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'popularity' },
        { kind: 'numeric', value: 1 },
      ]);
    });

    it('applyDefaultOperator handles FuncNode body (q.op=AND)', () => {
      const { ast, errors } = parseSolrQuery('{!func}sum(popularity, 1)', new Map([['q.op', 'AND']]));
      expect(errors).toEqual([]);
      const lp = ast as any;
      expect(lp.type).toBe('localParams');
      expect(lp.body.type).toBe('func');
      expect(lp.body.name).toBe('sum');
      expect(lp.body.args).toEqual([
        { kind: 'field', name: 'popularity' },
        { kind: 'numeric', value: 1 },
      ]);
    });

    it('transformNode throws for FuncNode', () => {
      const funcNode = {
        type: 'func' as const,
        name: 'sum',
        args: [
          { kind: 'field' as const, name: 'popularity' },
          { kind: 'numeric' as const, value: 1 },
        ],
      };
      const callTransform = () => transformNode(funcNode as any);
      expect(callTransform).toThrow('No transform rule registered for node type: func');
    });
  });
});
