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
      expect(mockTransform).toHaveBeenCalledWith(fakeAst);
      expect(result.dsl).toBe(fakeDsl);
      expect(result.warnings).toEqual([]);
    });

    it('defaults q to *:* when not provided', () => {
      mockParse.mockReturnValue({ ast: { type: 'matchAll' }, errors: [] });
      mockTransform.mockReturnValue(new Map([['match_all', new Map()]]));

      translateQ(params(['df', 'content']));

      expect(mockParse).toHaveBeenCalledWith('*:*', expect.any(Map));
    });
  });

  describe('parse failure', () => {
    it('returns passthrough with parse_error warnings', () => {
      mockParse.mockReturnValue({
        ast: null,
        errors: [{ message: 'Unexpected )', position: 16 }],
      });

      const result = translateQ(params(['q', 'title:java AND )']));

      expect(mockTransform).not.toHaveBeenCalled();
      expect(result.dsl.has('query_string')).toBe(true);
      expect((result.dsl.get('query_string') as Map<string, any>).get('query'))
        .toBe('title:java AND )');
      expect(result.warnings).toEqual([
        { construct: 'parse_error', position: 16, message: 'Unexpected )' },
      ]);
    });

    it('returns passthrough in partial mode on parse failure', () => {
      mockParse.mockReturnValue({
        ast: null,
        errors: [{ message: 'Parse error', position: 0 }],
      });

      const result = translateQ(params(['q', ')']), 'partial');

      expect(result.dsl.has('query_string')).toBe(true);
      expect(result.warnings[0].construct).toBe('parse_error');
    });
  });

  describe('transform failure', () => {
    it('returns passthrough in passthrough-on-error mode', () => {
      mockParse.mockReturnValue({ ast: { type: 'field', field: 'title', value: 'java' }, errors: [] });
      mockTransform.mockImplementation(() => { throw new Error('No transform rule registered for node type: field'); });

      const result = translateQ(params(['q', 'title:java']));

      expect(result.dsl.has('query_string')).toBe(true);
      expect((result.dsl.get('query_string') as Map<string, any>).get('query'))
        .toBe('title:java');
      expect(result.warnings).toEqual([
        { construct: 'transform_error', message: 'No transform rule registered for node type: field' },
      ]);
    });

    it('returns passthrough in partial mode', () => {
      mockParse.mockReturnValue({ ast: { type: 'field', field: 'title', value: 'java' }, errors: [] });
      mockTransform.mockImplementation(() => { throw new Error('No rule'); });

      const result = translateQ(params(['q', 'title:java']), 'partial');

      expect(result.dsl.has('query_string')).toBe(true);
      expect(result.warnings[0].construct).toBe('transform_error');
    });

  });
});
