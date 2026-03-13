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

const FEATURE_NAME = 'json-facets';

// region Terms facet

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
  if (sort) {
    termsInner.set('order', convertSort(sort));
  }

  const knownKeys = new Set([
    'type',
    'field',
    'limit',
    'offset',
    'mincount',
    'prefix',
    'missing',
    'sort',
  ]);
  warnUnknownKeys(def, knownKeys, 'terms');

  const result = new Map<string, any>([['terms', termsInner]]);

  return result;
}

// endregion

// region Range facet

/**
 * Parse a Solr range string like "[0,20)" or "[50,*]" into an OpenSearch range object.
 *
 * Solr range syntax: [inclusive or (exclusive for boundaries, * for unbounded.
 * OpenSearch range agg: `from` is inclusive, `to` is exclusive by default.
 */
function parseSolrRange(rangeStr: string): JavaMap {
  const range = new Map<string, any>();

  // Always preserve the original Solr range string as the bucket key
  // so OpenSearch bucket labels match Solr's (e.g., "[0,25)" instead of "0.0-25.0")
  range.set('key', rangeStr);

  // Match patterns like [0,20) or (10,*] or [50,*]
  const match = new RegExp(/^([[(])\s*([^,]*?)\s*,\s*([^)\]]*?)\s*([\])])$/).exec(rangeStr);
  if (!match) {
    return range;
  }

  const [, openBracket, lower, upper, closeBracket] = match;

  if (lower !== '*') {
    const lowerVal = Number(lower);
    range.set('from', Number.isNaN(lowerVal) ? lower : lowerVal);
  }

  if (upper !== '*') {
    const upperVal = Number(upper);
    range.set('to', Number.isNaN(upperVal) ? upper : upperVal);
  }

  // Warn if boundary semantics don't match OpenSearch defaults
  // OpenSearch default: from=inclusive, to=exclusive (equivalent to Solr's "[x,y)")
  if (openBracket === '(' || closeBracket === ']') {
    console.warn(
      `[${FEATURE_NAME}] Range boundary "${rangeStr}" may not map exactly to OpenSearch (default: from=inclusive, to=exclusive)`,
    );
  }

  return range;
}

/**
 * Convert a Solr range facet to an OpenSearch aggregation.
 *
 * Two modes:
 *   1. Uniform ranges (start/end/gap) → OpenSearch `histogram` aggregation
 *   2. Arbitrary ranges (ranges param) → OpenSearch `range` aggregation
 *
 * Solr range facet parameters:
 *   - field, start, end, gap  → histogram field, extended_bounds, interval
 *   - ranges                  → range aggregation with custom buckets
 *   - mincount                → min_doc_count
 *   - hardend                 → No direct OpenSearch equivalent (histogram always snaps to interval)
 *   - include (lower/upper/edge/outer/all) → No direct OpenSearch equivalent
 *   - other (before/after/between/none/all) → No direct OpenSearch equivalent
 */
function convertRangeFacet(def: JavaMap): JavaMap {
  const ranges = def.get('ranges');

  // Arbitrary ranges → OpenSearch `range` aggregation
  if (ranges != null) {
    return convertArbitraryRangeFacet(def, ranges);
  }

  // Uniform ranges → OpenSearch `histogram` aggregation
  return convertUniformRangeFacet(def);
}

/**
 * Convert a single range item (string, Map-like, or plain object) to an OpenSearch range entry.
 * Returns null if the item type is unrecognised.
 */
function convertRangeItem(item: any): JavaMap | null {
  if (typeof item === 'string') {
    return parseSolrRange(item);
  }
  if (isMapLike(item)) {
    return convertMapLikeRangeItem(item);
  }
  if (item && typeof item === 'object') {
    return convertPlainObjectRangeItem(item);
  }
  console.warn(
    `[${FEATURE_NAME}] Skipping unrecognised range item of type "${typeof item}": ${JSON.stringify(item)}`,
  );
  return null;
}

/** Convert a Map-like range item (from Jackson / GraalVM interop) to an OpenSearch range entry. */
function convertMapLikeRangeItem(item: JavaMap): JavaMap {
  const rangeStr = item.get('range');
  if (rangeStr && typeof rangeStr === 'string') {
    return parseSolrRange(rangeStr);
  }
  const osRange = new Map<string, any>();
  const from = item.get('from');
  const to = item.get('to');
  const key = item.get('key');
  if (from != null) osRange.set('from', from);
  if (to != null) osRange.set('to', to);
  if (key != null) osRange.set('key', key);
  return osRange;
}

/** Convert a plain JS object range item (from query-string JSON parse) to an OpenSearch range entry. */
function convertPlainObjectRangeItem(item: Record<string, any>): JavaMap {
  const rangeStr = item.range;
  if (rangeStr && typeof rangeStr === 'string') {
    return parseSolrRange(rangeStr);
  }
  const osRange = new Map<string, any>();
  if (item.from != null) osRange.set('from', item.from);
  if (item.to != null) osRange.set('to', item.to);
  if (item.key != null) osRange.set('key', item.key);
  return osRange;
}

/**
 * Convert Solr arbitrary ranges to an OpenSearch `range` aggregation.
 */
function convertArbitraryRangeFacet(def: JavaMap, ranges: any): JavaMap {
  const rangeInner = new Map<string, any>();
  rangeInner.set('field', def.get('field'));

  // ranges can be an array of objects with {range: "[0,20)"} or {from: 0, to: 20}
  const rangeItems = Array.isArray(ranges) ? ranges : Array.from(ranges as Iterable<any>);
  const osRanges: JavaMap[] = [];

  for (const item of rangeItems) {
    const converted = convertRangeItem(item);
    if (converted) {
      osRanges.push(converted);
    }
  }

  rangeInner.set('ranges', osRanges);

  const knownKeys = new Set(['type', 'field', 'ranges', 'mincount', 'hardend', 'include', 'other']);
  warnUnknownKeys(def, knownKeys, 'arbitrary range');

  return new Map<string, any>([['range', rangeInner]]);
}

/**
 * Convert Solr uniform range (start/end/gap) to an OpenSearch `histogram` aggregation.
 */
function convertUniformRangeFacet(def: JavaMap): JavaMap {
  const histogramInner = new Map<string, any>();
  histogramInner.set('field', def.get('field'));

  const gap = def.get('gap');
  if (gap != null) histogramInner.set('interval', gap);

  const start = def.get('start');
  const end = def.get('end');
  if (start != null || end != null) {
    const bounds = new Map<string, any>();
    if (start != null) bounds.set('min', start);
    // Solr's end is exclusive — the last bucket starts at (end - gap).
    // extended_bounds.max means "ensure a bucket containing this value exists",
    // so we use (end - gap) to avoid creating an extra bucket at `end`.
    if (end != null && gap != null) {
      bounds.set('max', end - gap);
    } else if (end != null) {
      bounds.set('max', end);
    }
    histogramInner.set('extended_bounds', bounds);
  }

  const mincount = def.get('mincount');
  if (mincount != null) histogramInner.set('min_doc_count', mincount);

  // These Solr parameters have no direct OpenSearch histogram equivalent — warn if present.
  const unsupportedParams: string[] = [];
  const hardend = def.get('hardend');
  if (hardend != null) unsupportedParams.push(`hardend=${hardend}`);

  const include = def.get('include');
  if (include != null) unsupportedParams.push(`include=${include}`);

  const other = def.get('other');
  if (other != null) unsupportedParams.push(`other=${other}`);

  if (unsupportedParams.length > 0) {
    console.warn(
      `[${FEATURE_NAME}] Range facet parameters with no direct OpenSearch histogram equivalent: ${unsupportedParams.join(', ')}`,
    );
  }

  const knownKeys = new Set([
    'type',
    'field',
    'start',
    'end',
    'gap',
    'mincount',
    'hardend',
    'include',
    'other',
  ]);
  warnUnknownKeys(def, knownKeys, 'range');

  return new Map<string, any>([['histogram', histogramInner]]);
}

// endregion

/** Log a warning for any keys in a facet definition that are not in the known set. */
function warnUnknownKeys(def: JavaMap, knownKeys: Set<string>, facetType: string): void {
  const unknownKeys: string[] = [];
  for (const key of def.keys()) {
    if (!knownKeys.has(key)) {
      unknownKeys.push(key);
    }
  }
  if (unknownKeys.length > 0) {
    console.warn(
      `[${FEATURE_NAME}] Unprocessed keys in ${facetType} facet definition: ${unknownKeys.join(', ')}`,
    );
  }
}

/**
 * Convert a single Solr facet definition to an OpenSearch agg Map.
 */
function convertSingleFacet(facetDef: any): JavaMap {
  if (!isMapLike(facetDef)) {
    return new Map();
  }

  const type = (facetDef.get('type') || '').toString().toLowerCase();

  switch (type) {
    case 'terms':
      return convertTermsFacet(facetDef);
    case 'range':
      return convertRangeFacet(facetDef);
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
  name: FEATURE_NAME,
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
