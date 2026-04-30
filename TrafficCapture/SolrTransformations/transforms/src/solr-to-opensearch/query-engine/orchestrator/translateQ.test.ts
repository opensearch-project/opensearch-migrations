import { describe, it, expect, vi, beforeEach } from 'vitest';
import { translateQ } from './translateQ';
import type { ASTNode } from '../ast/nodes';

// vi.mock must come before importing the mocked modules —
// vitest hoists these calls to the top of the file.
vi.mock('../parser/parser', () => ({
  parseSolrQuery: vi.fn(),
}));
vi.mock('../transformer/astToOpenSearch', () => ({
  transformNode: vi.fn(),
}));

import { parseSolrQuery } from '../parser/parser';
import { transformNode } from '../transformer/astToOpenSearch';

const mockParse = vi.mocked(parseSolrQuery);
const mockTransform = vi.mocked(transformNode);

const params = (...entries: [string, string][]) => new Map(entries);

beforeEach(() => {
  vi.resetAllMocks();
});

describe('translateQ', () => {
  describe('successful translation', () => {
    it('passes q and params to parser, then AST to transformer', () => {
      const fakeAst: ASTNode = { type: 'matchAll' };
      const fakeDsl = new Map([['match_all', new Map()]]);
      mockParse.mockReturnValue({ ast: fakeAst, errors: [] });
      mockTransform.mockReturnValue(fakeDsl);

      const result = translateQ(params(['q', 'title:java'], ['df', 'content']));

      expect(mockParse).toHaveBeenCalledWith('title:java', params(['q', 'title:java'], ['df', 'content']));
      expect(mockTransform).toHaveBeenCalledWith(fakeAst, new Map());
      expect(result.dsl).toBe(fakeDsl);
      expect(result.warnings).toEqual([]);
    });

    it('defaults q to *:* when not provided', () => {
      mockParse.mockReturnValue({ ast: { type: 'matchAll' }, errors: [] });
      mockTransform.mockReturnValue(new Map([['match_all', new Map()]]));

      translateQ(params(['df', 'content']));

      expect(mockParse).toHaveBeenCalledWith('*:*', expect.any(Map));
    });

    it('passes fieldTypes to transformNode when provided', () => {
      const fakeAst: ASTNode = { type: 'matchAll' };
      const fakeDsl = new Map([['match_all', new Map()]]);
      const fieldTypes = new Map([['status', 'solr.StrField'], ['title', 'solr.TextField']]);
      mockParse.mockReturnValue({ ast: fakeAst, errors: [] });
      mockTransform.mockReturnValue(fakeDsl);

      translateQ(params(['q', 'status:active']), 'fail-fast', fieldTypes);

      expect(mockTransform).toHaveBeenCalledWith(fakeAst, fieldTypes);
    });

    it('passes empty fieldTypes to transformNode when not provided', () => {
      const fakeAst: ASTNode = { type: 'matchAll' };
      mockParse.mockReturnValue({ ast: fakeAst, errors: [] });
      mockTransform.mockReturnValue(new Map([['match_all', new Map()]]));

      translateQ(params(['q', '*:*']));

      // Default empty map is passed — transformNode always receives fieldTypes
      expect(mockTransform).toHaveBeenCalledWith(fakeAst, new Map());
    });
  });

  describe('parse failure', () => {
    it('returns passthrough with parse_error warnings', () => {
      mockParse.mockReturnValue({
        ast: null,
        errors: [{ message: 'Unexpected )', position: 16 }],
      });

      const result = translateQ(params(['q', 'title:java AND )']), 'passthrough-on-error');

      expect(mockTransform).not.toHaveBeenCalled();
      expect(result.dsl.has('query_string')).toBe(true);
      expect((result.dsl.get('query_string') as Map<string, any>).get('query'))
        .toBe('title:java AND )');
      expect(result.warnings).toEqual([
        { construct: 'parse_error', position: 16, message: 'Unexpected )' },
      ]);
    });
  });

  describe('transform failure', () => {
    it('returns passthrough in passthrough-on-error mode', () => {
      mockParse.mockReturnValue({ ast: { type: 'field', field: 'title', value: 'java' }, errors: [] });
      mockTransform.mockImplementation(() => { throw new Error('No transform rule registered for node type: field'); });

      const result = translateQ(params(['q', 'title:java']), 'passthrough-on-error');

      expect(result.dsl.has('query_string')).toBe(true);
      expect((result.dsl.get('query_string') as Map<string, any>).get('query'))
        .toBe('title:java');
      expect(result.warnings).toEqual([
        { construct: 'transform_error', message: 'No transform rule registered for node type: field' },
      ]);
    });
  });

  describe('pf phrase boost', () => {
    it('wraps dsl in bool when pf is set', () => {
      const fakeDsl = new Map([['multi_match', new Map()]]);
      mockParse.mockReturnValue({ ast: { type: 'matchAll' }, errors: [] });
      mockTransform.mockReturnValue(fakeDsl);

      const result = translateQ(params(['q', 'foo bar'], ['pf', 'title^50']));

      expect(result.dsl.has('bool')).toBe(true);
      expect((result.dsl.get('bool') as Map<string, any>).get('must')).toBe(fakeDsl);
    });

    it('returns plain dsl when pf is not set', () => {
      const fakeDsl = new Map([['match_all', new Map()]]);
      mockParse.mockReturnValue({ ast: { type: 'matchAll' }, errors: [] });
      mockTransform.mockReturnValue(fakeDsl);

      const result = translateQ(params(['q', 'foo bar']));

      expect(result.dsl).toBe(fakeDsl);
    });
  });

  describe('fail-fast mode', () => {
    it('throws on parse failure', () => {
      mockParse.mockReturnValue({
        ast: null,
        errors: [{ message: 'Unexpected )', position: 16 }],
      });

      expect(() => translateQ(params(['q', 'title:java AND )']), 'fail-fast'))
        .toThrow('Failed to parse Solr query: title:java AND )');
    });

    it('re-throws on transform failure', () => {
      mockParse.mockReturnValue({ ast: { type: 'field', field: 'title', value: 'java' }, errors: [] });
      mockTransform.mockImplementation(() => { throw new Error('No rule for field'); });

      expect(() => translateQ(params(['q', 'title:java']), 'fail-fast'))
        .toThrow('Failed to transform Solr query: title:java');
    });

    it('returns normally on successful translation', () => {
      const fakeDsl = new Map([['match_all', new Map()]]);
      mockParse.mockReturnValue({ ast: { type: 'matchAll' }, errors: [] });
      mockTransform.mockReturnValue(fakeDsl);

      const result = translateQ(params(['q', '*:*']), 'fail-fast');
      expect(result.dsl).toBe(fakeDsl);
      expect(result.warnings).toEqual([]);
    });
  });
});
