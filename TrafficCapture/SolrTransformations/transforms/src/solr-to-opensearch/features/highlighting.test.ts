import { describe, it, expect, vi } from 'vitest';
import { request, response } from './highlighting';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

function buildReqCtx(params: string): RequestContext {
  return {
    msg: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    params: new URLSearchParams(params),
    body: new Map() as unknown as JavaMap,
  };
}

function buildRespCtx(responseBody: Map<string, any>, params: string): ResponseContext {
  return {
    request: new Map() as unknown as JavaMap,
    response: new Map() as unknown as JavaMap,
    endpoint: 'select',
    collection: 'testcollection',
    requestParams: new URLSearchParams(params),
    responseBody: responseBody as unknown as JavaMap,
  };
}

describe('highlighting MicroTransform', () => {
  describe('request.match', () => {
    it('should match when hl=true', () => {
      expect(request.match!(buildReqCtx('hl=true'))).toBe(true);
    });

    it('should not match when hl is absent', () => {
      expect(request.match!(buildReqCtx(''))).toBe(false);
    });

    it('should not match when hl=false', () => {
      expect(request.match!(buildReqCtx('hl=false'))).toBe(false);
    });
  });

  describe('request.apply', () => {
    it('should set highlight block with specified fields', () => {
      const ctx = buildReqCtx('hl=true&hl.fl=title,description');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      const fields = hl.get('fields') as Map<string, any>;
      expect([...fields.keys()]).toEqual(['title', 'description']);
    });

    it('should default to wildcard field when hl.fl is absent', () => {
      const ctx = buildReqCtx('hl=true');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      const fields = hl.get('fields') as Map<string, any>;
      expect([...fields.keys()]).toEqual(['*']);
    });

    it('should set number_of_fragments to 1 by default (Solr default)', () => {
      const ctx = buildReqCtx('hl=true');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('number_of_fragments')).toBe(1);
    });

    it('should use hl.snippets for number_of_fragments', () => {
      const ctx = buildReqCtx('hl=true&hl.snippets=3');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('number_of_fragments')).toBe(3);
    });

    it('should set fragment_size from hl.fragsize', () => {
      const ctx = buildReqCtx('hl=true&hl.fragsize=200');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('fragment_size')).toBe(200);
    });

    it('should set pre_tags and post_tags from hl.simple.pre/post', () => {
      const ctx = buildReqCtx('hl=true&hl.simple.pre=<b>&hl.simple.post=</b>');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('pre_tags')).toEqual(['<b>']);
      expect(hl.get('post_tags')).toEqual(['</b>']);
    });

    it('should set pre_tags and post_tags from hl.tag.pre/post', () => {
      const ctx = buildReqCtx('hl=true&hl.tag.pre=<mark>&hl.tag.post=</mark>');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('pre_tags')).toEqual(['<mark>']);
      expect(hl.get('post_tags')).toEqual(['</mark>']);
    });

    it('should set require_field_match to false by default (Solr default)', () => {
      const ctx = buildReqCtx('hl=true');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('require_field_match')).toBe(false);
    });

    it('should set require_field_match to true when hl.requireFieldMatch=true', () => {
      const ctx = buildReqCtx('hl=true&hl.requireFieldMatch=true');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('require_field_match')).toBe(true);
    });

    it('should map hl.method to OpenSearch type', () => {
      for (const [solr, os] of [['unified', 'unified'], ['original', 'plain'], ['fastVector', 'fvh']]) {
        const ctx = buildReqCtx(`hl=true&hl.method=${solr}`);
        request.apply(ctx);
        const hl = ctx.body.get('highlight') as Map<string, any>;
        expect(hl.get('type')).toBe(os);
      }
    });

    it('should set encoder from hl.encoder', () => {
      const ctx = buildReqCtx('hl=true&hl.encoder=html');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('encoder')).toBe('html');
    });

    it('should set max_analyzer_offset from hl.maxAnalyzedChars', () => {
      const ctx = buildReqCtx('hl=true&hl.maxAnalyzedChars=50000');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.get('max_analyzer_offset')).toBe(50000);
    });

    it('should warn on unsupported hl.method value', () => {
      const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const ctx = buildReqCtx('hl=true&hl.method=unknownMethod');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      expect(hl.has('type')).toBe(false);
      expect(spy).toHaveBeenCalledWith(expect.stringContaining("Unsupported hl.method 'unknownMethod'"));
      spy.mockRestore();
    });

    it('should route hl.q through query engine', () => {
      const ctx = buildReqCtx('hl=true&hl.q=title:search');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      const hlQuery = hl.get('highlight_query') as Map<string, any>;
      expect(hlQuery.get('term')).toBeDefined();
      expect(hlQuery.get('term').get('title')).toBe('search');
    });

    it('should translate hl.q=*:* to match_all', () => {
      const ctx = buildReqCtx('hl=true&hl.q=*:*');
      request.apply(ctx);
      const hl = ctx.body.get('highlight') as Map<string, any>;
      const hlQuery = hl.get('highlight_query') as Map<string, any>;
      expect(hlQuery.get('match_all')).toBeDefined();
    });

    it('should warn on unsupported params', () => {
      const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const ctx = buildReqCtx('hl=true&hl.alternateField=text&hl.mergeContiguous=true');
      request.apply(ctx);
      expect(spy).toHaveBeenCalledTimes(2);
      spy.mockRestore();
    });
  });

  describe('response.match', () => {
    it('should match when request had hl=true', () => {
      const body = new Map<string, any>();
      expect(response.match!(buildRespCtx(body, 'hl=true'))).toBe(true);
    });

    it('should not match when request had no hl', () => {
      const body = new Map<string, any>();
      expect(response.match!(buildRespCtx(body, ''))).toBe(false);
    });
  });

  describe('response.apply', () => {
    it('should extract per-hit highlights into top-level highlighting section', () => {
      const body = new Map<string, any>([
        ['hits', new Map([
          ['hits', [
            new Map<string, any>([['_id', '1'], ['highlight', new Map([['title', ['<em>test</em>']]])]]),
            new Map<string, any>([['_id', '2'], ['highlight', new Map([['title', ['<em>doc</em>']]])]]),
          ]],
        ])],
      ]);
      const ctx = buildRespCtx(body, 'hl=true');
      response.apply(ctx);
      const hl = ctx.responseBody.get('highlighting') as Map<string, any>;
      expect(hl.get('1').get('title')).toEqual(['<em>test</em>']);
      expect(hl.get('2').get('title')).toEqual(['<em>doc</em>']);
    });

    it('should remove per-hit highlight after extraction', () => {
      const hit = new Map<string, any>([['_id', '1'], ['highlight', new Map([['title', ['x']]])]]);
      const body = new Map<string, any>([['hits', new Map([['hits', [hit]]])]]);
      const ctx = buildRespCtx(body, 'hl=true');
      response.apply(ctx);
      expect(hit.has('highlight')).toBe(false);
    });

    it('should include empty map for hits without highlights (Solr behavior)', () => {
      const body = new Map<string, any>([['hits', new Map([['hits', [
        new Map<string, any>([['_id', '1']]),
      ]]])]]);
      const ctx = buildRespCtx(body, 'hl=true');
      response.apply(ctx);
      const hl = ctx.responseBody.get('highlighting') as Map<string, any>;
      expect(hl.get('1').size).toBe(0);
    });

    it('should handle missing hits gracefully', () => {
      const body = new Map<string, any>();
      const ctx = buildRespCtx(body, 'hl=true');
      response.apply(ctx);
      expect(ctx.responseBody.has('highlighting')).toBe(false);
    });
  });
});
