/**
 * JSON request body — normalize Solr JSON body format into URL params.
 *
 * Solr accepts queries as either URL params or a JSON request body:
 *   GET  /select?q=title:java&rows=10
 *   POST /select  {"query":"title:java","limit":10}
 *
 * The JSON body uses different key names than URL params:
 *   query  → q        limit  → rows      offset → start
 *   sort   → sort     filter → fq        fields → fl
 *   params → merged into ctx.params (arbitrary key-value pairs)
 *
 * This pre-processor runs first in the select pipeline. It reads the
 * incoming JSON body, maps keys to URL param equivalents, merges them
 * into ctx.params, and clears the body so downstream transforms can
 * write the OpenSearch request body fresh.
 *
 * JSON body keys take precedence over URL params (verified against Solr 8/9).
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, JavaMap } from '../context';

/**
 * Solr JSON body key → URL param name.
 *
 * Complete mapping per Solr's JSON Request API:
 * https://solr.apache.org/guide/solr/latest/query-guide/json-request-api.html#supported-properties-and-syntax
 *
 * Not yet supported:
 *   - json.<param_name> generic passthrough (URL-param-to-JSON bridge, opposite direction)
 *   - queries (JSON Query DSL additional named queries, depends on local params {!v=$...})
 */
const KEY_MAP: Record<string, string> = {
  query: 'q',
  limit: 'rows',
  offset: 'start',
  sort: 'sort',
  filter: 'fq',
  fields: 'fl',
};

/** Map JSON body keys to URL params. JSON body takes precedence over URL params (verified against Solr 8/9). */
function mapBodyKeysToParams(body: JavaMap, params: URLSearchParams): void {
  for (const [jsonKey, paramKey] of Object.entries(KEY_MAP)) {
    if (!body.has(jsonKey)) continue;
    const val = body.get(jsonKey);
    params.set(paramKey, Array.isArray(val) ? val.join(',') : String(val));
  }
}

/** Merge the "params" object from JSON body into URL params. */
function mergeParamsObject(body: JavaMap, params: URLSearchParams): void {
  const paramsObj = body.get('params');
  if (!paramsObj || typeof paramsObj.entries !== 'function') return;
  for (const [key, val] of paramsObj.entries()) {
    if (!params.has(key)) params.set(key, String(val));
  }
}

/** Move "facet" to "json.facet" key for downstream json-facets transform. */
function moveFacetKey(body: JavaMap, params: URLSearchParams): void {
  if (body.has('facet') && !params.has('json.facet')) {
    body.set('json.facet', body.get('facet'));
  }
}

/** Remove all JSON body keys that were mapped to params. */
function clearMappedKeys(body: JavaMap): void {
  for (const jsonKey of Object.keys(KEY_MAP)) body.delete(jsonKey);
  body.delete('params');
  body.delete('facet');
}

export const request: MicroTransform<RequestContext> = {
  name: 'json-request',
  match: (ctx) => ctx.body.size > 0 && ctx.body.has('query'),
  apply: (ctx) => {
    mapBodyKeysToParams(ctx.body, ctx.params);
    mergeParamsObject(ctx.body, ctx.params);
    moveFacetKey(ctx.body, ctx.params);
    clearMappedKeys(ctx.body);
  },
};
