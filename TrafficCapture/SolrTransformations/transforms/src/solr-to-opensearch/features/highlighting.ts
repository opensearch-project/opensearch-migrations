/**
 * Highlighting — translate Solr hl.* params to OpenSearch highlight DSL,
 * and restructure the per-hit highlight response into Solr's top-level
 * highlighting section.
 *
 * Request: hl=true + hl.* params → { "highlight": { ... } }
 * Response: hits[].highlight → { "highlighting": { docId: { field: [fragments] } } }
 *
 * Key default differences handled:
 *   - hl.snippets (Solr default: 1) → number_of_fragments (OS default: 5)
 *   - hl.requireFieldMatch (Solr default: false) → require_field_match (OS default: true)
 *   - hl.method → type (unified→unified, original→plain, fastVector→fvh)
 *
 * Unsupported Solr params emit console warnings per MVP requirements.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

// Solr hl params with no OpenSearch equivalent — warn and skip
const UNSUPPORTED_PARAMS = [
  'hl.alternateField',
  'hl.maxAlternateFieldLength',
  'hl.mergeContiguous',
  'hl.preserveMulti',
  'hl.fragmenter',
  'hl.tag.ellipsis',
  'hl.fragListBuilder',
  'hl.boundaryScanner',
];

// Solr hl.method → OpenSearch type
const HIGHLIGHTER_TYPE_MAP: Record<string, string> = {
  unified: 'unified',
  original: 'plain',
  fastVector: 'fvh',
};

export const request: MicroTransform<RequestContext> = {
  name: 'highlighting',
  match: (ctx) => ctx.params.get('hl') === 'true',
  apply: (ctx) => {
    const p = ctx.params;
    const highlight = new Map<string, unknown>();

    // Fields
    const hlFl = p.get('hl.fl');
    if (hlFl) {
      const fields = new Map<string, unknown>();
      for (const f of hlFl.split(',')) {
        fields.set(f.trim(), new Map());
      }
      highlight.set('fields', fields);
    } else {
      // Default: highlight all queryable fields
      highlight.set('fields', new Map([['*', new Map()]]));
    }

    // Highlighter type
    const method = p.get('hl.method');
    if (method && HIGHLIGHTER_TYPE_MAP[method]) {
      highlight.set('type', HIGHLIGHTER_TYPE_MAP[method]);
    }

    // number_of_fragments — Solr default is 1, OS default is 5
    const snippets = p.get('hl.snippets');
    highlight.set('number_of_fragments', snippets ? parseInt(snippets, 10) : 1);

    // fragment_size
    const fragsize = p.get('hl.fragsize');
    if (fragsize) {
      highlight.set('fragment_size', parseInt(fragsize, 10));
    }

    // Pre/post tags — Solr has two naming conventions depending on highlighter
    const pre = p.get('hl.simple.pre') || p.get('hl.tag.pre');
    const post = p.get('hl.simple.post') || p.get('hl.tag.post');
    if (pre) highlight.set('pre_tags', [pre]);
    if (post) highlight.set('post_tags', [post]);

    // require_field_match — Solr default: false, OS default: true
    const rfm = p.get('hl.requireFieldMatch');
    highlight.set('require_field_match', rfm === 'true');

    // Encoder
    const encoder = p.get('hl.encoder');
    if (encoder) highlight.set('encoder', encoder);

    // Max analyzed chars
    const maxChars = p.get('hl.maxAnalyzedChars');
    if (maxChars) highlight.set('max_analyzer_offset', parseInt(maxChars, 10));

    // Highlight query override
    const hlQ = p.get('hl.q');
    if (hlQ) {
      highlight.set('highlight_query', new Map([['query_string', new Map([['query', hlQ]])]]));
    }

    ctx.body.set('highlight', highlight);

    // Warn on unsupported params
    for (const param of UNSUPPORTED_PARAMS) {
      if (p.has(param)) {
        console.warn(`[highlighting] Unsupported Solr param '${param}' — no OpenSearch equivalent, skipped.`);
      }
    }
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'highlighting',
  match: (ctx) => ctx.requestParams.get('hl') === 'true',
  apply: (ctx) => {
    const hits: JavaMap | undefined = ctx.responseBody.get('hits');
    if (!hits) return;
    const hitsArray: JavaMap[] = hits.get('hits');
    if (!hitsArray) return;

    const highlighting = new Map<string, unknown>();

    for (const hit of hitsArray) {
      const hl: JavaMap | undefined = hit.get('highlight');
      if (!hl) continue;

      const docId: string = hit.get('_id');
      const fieldHighlights = new Map<string, unknown>();

      for (const field of hl.keys()) {
        fieldHighlights.set(field, hl.get(field));
      }

      highlighting.set(docId, fieldHighlights);
      // Remove per-hit highlight so hits-to-docs doesn't carry it
      hit.delete('highlight');
    }

    if (highlighting.size > 0) {
      ctx.responseBody.set('highlighting', highlighting);
    }
  },
};
