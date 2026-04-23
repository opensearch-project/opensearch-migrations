import { describe, it, expect, beforeAll } from 'vitest';
import { request, initValidation } from './validation';
import type { RequestContext, JavaMap } from '../context';

beforeAll(() => {
  initValidation(
    new Set(['q', 'rows', 'start', 'sort', 'fl', 'cursorMark', 'hl', 'json.facet', 'wt', 'indent', 'echoParams', 'df', 'bq', 'defType', 'qf']),
    ['hl.', 'json.facet.'],
    [
      { name: 'rows', type: 'integer' },
      { name: 'start', type: 'integer' },
      { name: 'hl', type: 'boolean' },
      { name: 'hl.snippets', type: 'integer' },
      { name: 'hl.fragsize', type: 'integer' },
      { name: 'hl.maxAnalyzedChars', type: 'integer' },
      { name: 'hl.requireFieldMatch', type: 'boolean' },
      { name: 'json.facet', type: 'json' },
      { name: 'q', type: 'rejectPattern', pattern: String.raw`^\{!`, reason: 'Local params ({!...}) syntax in q is not supported' },
      { name: 'sort', type: 'rejectPattern', pattern: String.raw`\{!`, reason: 'Local params ({!...}) syntax in sort is not supported' },
      { name: 'fl', type: 'rejectPattern', pattern: String.raw`\{!`, reason: 'Local params ({!...}) syntax in fl is not supported' },
      { name: 'bq', type: 'rejectPattern', pattern: String.raw`^\{!`, reason: 'Local params ({!...}) syntax in bq is not supported' },
    ],
  );
});

function buildCtx(params: string): RequestContext {
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams(params),
    body: new Map() as unknown as JavaMap,
  };
}

describe('validation MicroTransform', () => {
  describe('match', () => {
    it('matches select endpoint', () => {
      expect(request.match!(buildCtx('q=*:*'))).toBe(true);
    });

    it('does not match non-select endpoints', () => {
      const ctx = buildCtx('q=*:*');
      ctx.endpoint = 'update';
      expect(request.match!(ctx)).toBe(false);
    });
  });

  describe('integer param validation', () => {
    it('passes with valid rows and start', () => {
      expect(() => request.apply(buildCtx('q=*:*&rows=10&start=0'))).not.toThrow();
    });

    it('throws when rows is non-numeric', () => {
      expect(() => request.apply(buildCtx('q=*:*&rows=abc'))).toThrow("'rows' must be a valid integer, got 'abc'");
    });

    it('throws when start is non-numeric', () => {
      expect(() => request.apply(buildCtx('q=*:*&start=xyz'))).toThrow("'start' must be a valid integer, got 'xyz'");
    });

    it('throws when rows is empty', () => {
      expect(() => request.apply(buildCtx('q=*:*&rows='))).toThrow("'rows' must be a valid integer, got ''");
    });

    it('passes when integer params are absent', () => {
      expect(() => request.apply(buildCtx('q=*:*'))).not.toThrow();
    });

    it('throws when hl.snippets is non-numeric', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true&hl.snippets=many'))).toThrow("'hl.snippets' must be a valid integer");
    });

    it('throws when hl.fragsize is non-numeric', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true&hl.fragsize=big'))).toThrow("'hl.fragsize' must be a valid integer");
    });

    it('throws when hl.maxAnalyzedChars is non-numeric', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true&hl.maxAnalyzedChars=lots'))).toThrow("'hl.maxAnalyzedChars' must be a valid integer");
    });
  });

  describe('boolean param validation', () => {
    it('passes with hl=true', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true'))).not.toThrow();
    });

    it('passes with hl=false', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=false'))).not.toThrow();
    });

    it('throws when hl has invalid boolean value', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=yes'))).toThrow("'hl' must be 'true' or 'false', got 'yes'");
    });

    it('throws when hl=1', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=1'))).toThrow("'hl' must be 'true' or 'false', got '1'");
    });

    it('throws when hl.requireFieldMatch is invalid', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true&hl.requireFieldMatch=yes'))).toThrow("'hl.requireFieldMatch' must be 'true' or 'false'");
    });

    it('passes with hl.requireFieldMatch=true', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true&hl.requireFieldMatch=true'))).not.toThrow();
    });
  });

  describe('json param validation', () => {
    it('passes with valid json.facet', () => {
      expect(() => request.apply(buildCtx('q=*:*&json.facet={"categories":{"type":"terms","field":"cat"}}'))).not.toThrow();
    });

    it('throws when json.facet is invalid JSON', () => {
      expect(() => request.apply(buildCtx('q=*:*&json.facet={bad json}'))).toThrow("'json.facet' must be valid JSON");
    });

    it('throws when json.facet is empty', () => {
      expect(() => request.apply(buildCtx('q=*:*&json.facet='))).toThrow("'json.facet' must be valid JSON, got empty value");
    });
  });

  describe('rejectPattern — local params detection', () => {
    it('throws when q uses local params', () => {
      expect(() => request.apply(buildCtx('q={!dismax qf=title}hello'))).toThrow('Local params ({!...}) syntax in q is not supported');
    });

    it('throws when q uses func local params', () => {
      expect(() => request.apply(buildCtx('q={!func}popularity'))).toThrow('Local params ({!...}) syntax in q is not supported');
    });

    it('passes when q is a normal query', () => {
      expect(() => request.apply(buildCtx('q=title:hello'))).not.toThrow();
    });

    it('throws when sort uses local params', () => {
      expect(() => request.apply(buildCtx('q=*:*&sort={!func}popularity desc'))).toThrow('Local params ({!...}) syntax in sort is not supported');
    });

    it('passes when sort is normal', () => {
      expect(() => request.apply(buildCtx('q=*:*&sort=price asc'))).not.toThrow();
    });

    it('throws when fl uses local params', () => {
      expect(() => request.apply(buildCtx('q=*:*&fl=id,{!func}div(price,2)'))).toThrow('Local params ({!...}) syntax in fl is not supported');
    });

    it('passes when fl is normal', () => {
      expect(() => request.apply(buildCtx('q=*:*&fl=id,title,price'))).not.toThrow();
    });

    it('throws when bq uses local params', () => {
      expect(() => request.apply(buildCtx('q=*:*&defType=edismax&qf=title&bq={!boost b=2}category:food'))).toThrow('Local params ({!...}) syntax in bq is not supported');
    });

    it('throws when bq uses func local params', () => {
      expect(() => request.apply(buildCtx('q=*:*&defType=edismax&qf=title&bq={!func}sum(1,price)'))).toThrow('Local params ({!...}) syntax in bq is not supported');
    });

    it('passes when bq is a normal boost query', () => {
      expect(() => request.apply(buildCtx('q=*:*&defType=edismax&qf=title&bq=category:food^10'))).not.toThrow();
    });
  });

  describe('unsupported param detection', () => {
    it('passes with all supported params', () => {
      expect(() => request.apply(buildCtx('q=*:*&rows=10&start=0&sort=price+asc&fl=id,title&wt=json'))).not.toThrow();
    });

    it('passes with hl prefix params', () => {
      expect(() => request.apply(buildCtx('q=*:*&hl=true&hl.fl=title&hl.snippets=3'))).not.toThrow();
    });

    it('passes with json.facet prefix params', () => {
      expect(() => request.apply(buildCtx('q=*:*&json.facet={"categories":{"type":"terms","field":"cat"}}'))).not.toThrow();
    });

    it('throws on unsupported param fq', () => {
      expect(() => request.apply(buildCtx('q=*:*&fq=inStock:true'))).toThrow('Unsupported Solr parameters: fq');
    });

    it('throws on multiple unsupported params', () => {
      expect(() => request.apply(buildCtx('q=*:*&fq=inStock:true&facet=true'))).toThrow('Unsupported Solr parameters: fq, facet');
    });

    it('skips internal _ prefixed params', () => {
      expect(() => request.apply(buildCtx('q=*:*&_=1234567890'))).not.toThrow();
    });

    it('passes with cursorMark', () => {
      expect(() => request.apply(buildCtx('q=*:*&cursorMark=*&sort=id+asc'))).not.toThrow();
    });

    it('passes with common params df, indent, echoParams', () => {
      expect(() => request.apply(buildCtx('q=*:*&df=title&indent=true&echoParams=all'))).not.toThrow();
    });
  });
});
