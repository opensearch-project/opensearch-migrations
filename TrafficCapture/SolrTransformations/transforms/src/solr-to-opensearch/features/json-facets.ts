/**
 * JSON Facet API — convert Solr json.facet to OpenSearch aggs.
 *
 * Handles the json.facet parameter from either:
 *   - The JSON request body (as a nested Map)
 *   - The query string parameter json.facet (JSON-encoded string)
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, JavaMap } from '../context';
import { convertSort, isMapLike } from './utils';

function convertTermsFacet(def: JavaMap): JavaMap {
  const termsInner = new Map<string, any>();
  termsInner.set('field', def.get('field'));

  const limit = def.get('limit');
  if (limit != null) termsInner.set('size', limit);

  const offset = def.get('offset');
  if (offset != null) termsInner.set('shard_size', offset);

  const mincount = def.get('mincount');
  if (mincount != null) termsInner.set('min_doc_count', mincount);

  const prefix = def.get('prefix');
  if (prefix != null) termsInner.set('include', `${prefix}.*`);

  const missing = def.get('missing');
  if (missing === true) termsInner.set('missing', '');

  const sort = def.get('sort');
  if (sort && typeof sort === 'string') {
    termsInner.set('order', convertSort(sort));
  }

  const result = new Map<string, any>([['terms', termsInner]]);

  return result;
}

/**
 * Convert a single Solr facet definition to an OpenSearch agg Map.
 */
function convertSingleFacet(facetDef: any): JavaMap {
  if (!isMapLike(facetDef)) {
    return new Map();
  }

  const type = (facetDef.get('type') || '').toString().toLowerCase();

  switch (type) {  // NOSONAR
    case 'terms':
      return convertTermsFacet(facetDef);
    default:
      throw new Error(`Facet type '${type}' is not implemented`);
  }
}

/**
 * Convert an entire Solr json.facet object to an OpenSearch aggs object.
 *
 * @param solrJsonFacet - The Solr json.facet Map (keys are facet names, values are facet definitions)
 * @returns An OpenSearch aggs Map ready to set on the request body
 */
export function convertJsonFacets(solrJsonFacet: JavaMap): JavaMap {
  const aggs = new Map<string, any>();
  for (const name of solrJsonFacet.keys()) {
    const facetDef = solrJsonFacet.get(name);
    aggs.set(name, convertSingleFacet(facetDef));
  }
  return aggs;
}

export const request: MicroTransform<RequestContext> = {
  name: 'json-facets',
  match: (ctx) => ctx.body.has('json.facet') || ctx.params.has('json.facet'),
  apply: (ctx) => {
    let facetMap: JavaMap | undefined;

    // Prefer body — it's already a parsed Map from Jackson
    const bodyFacet = ctx.body.get('json.facet');
    if (isMapLike(bodyFacet)) {
      facetMap = bodyFacet;
      ctx.body.delete('json.facet');
    } else {
      // Fall back to query-string param (JSON string → parse into Map)
      const paramVal = ctx.params.get('json.facet');
      if (paramVal) {
        facetMap = toNestedMap(JSON.parse(paramVal));
      }
    }

    if (facetMap && facetMap.size > 0) {
      ctx.body.set('aggs', convertJsonFacets(facetMap));
    }
  },
};

/** Recursively convert a plain JS object to nested Maps (for query-string fallback). */
function toNestedMap(obj: Record<string, any>): JavaMap {
  const m = new Map<string, any>();
  for (const [k, v] of Object.entries(obj)) {
    m.set(k, v && typeof v === 'object' && !Array.isArray(v) ? toNestedMap(v) : v);
  }
  return m;
}
