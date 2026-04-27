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
import type { ParamRule } from './validation';
import { convertSort, isMapLike, isSolrDateMathGap, convertSolrDateGap } from './utils';
import { parseFuncQuery } from '../query-engine/parser/parser';
import type { FuncNode } from '../query-engine/ast/nodes';

/** Solr query params this feature handles. */
export const params = ['json.facet'];
export const paramPrefixes = ['json.facet.'];
export const paramRules: ParamRule[] = [
  { name: 'json.facet', type: 'json' },
];

const FEATURE_NAME = 'json-facets';

// region Terms facet

function convertTermsFacet(def: JavaMap, ctx: RequestContext): JavaMap {
  const termsInner = new Map<string, any>();
  termsInner.set('field', def.get('field'));

  let offset = def.get('offset');
  let limit = def.get('limit');

  // OpenSearch has no concept of offset. So, return a large result and
  // expect the client to take the last `limit` results.
  if (offset != null || limit != null) {
    offset ??= 0;
    limit ??= 10;
    termsInner.set('size', offset + limit);
    if (offset > 0) {
      ctx.emitMetric('terms_offset');
    }
  }

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
    'facet',
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
function parseSolrRange(rangeStr: string, ctx: RequestContext): JavaMap {
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
    ctx.emitMetric('range_boundary');
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
function convertRangeFacet(def: JavaMap, ctx: RequestContext): JavaMap {
  const ranges = def.get('ranges');

  // Arbitrary ranges → OpenSearch `range` aggregation
  if (ranges != null) {
    return convertArbitraryRangeFacet(def, ranges, ctx);
  }

  // Uniform ranges → OpenSearch `histogram` aggregation
  return convertUniformRangeFacet(def, ctx);
}

/**
 * Convert a single range item (string, Map-like, or plain object) to an OpenSearch range entry.
 * Returns null if the item type is unrecognised.
 */
function convertRangeItem(item: any, ctx: RequestContext): JavaMap | null {
  if (typeof item === 'string') {
    return parseSolrRange(item, ctx);
  }
  if (isMapLike(item)) {
    return convertMapLikeRangeItem(item, ctx);
  }
  if (item && typeof item === 'object') {
    return convertPlainObjectRangeItem(item, ctx);
  }
  console.warn(
    `[${FEATURE_NAME}] Skipping unrecognised range item of type "${typeof item}": ${JSON.stringify(item)}`,
  );
  return null;
}

/** Convert a Map-like range item (from Jackson / GraalVM interop) to an OpenSearch range entry. */
function convertMapLikeRangeItem(item: JavaMap, ctx: RequestContext): JavaMap {
  const rangeStr = item.get('range');
  if (rangeStr && typeof rangeStr === 'string') {
    return parseSolrRange(rangeStr, ctx);
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
function convertPlainObjectRangeItem(item: Record<string, any>, ctx: RequestContext): JavaMap {
  const rangeStr = item.range;
  if (rangeStr && typeof rangeStr === 'string') {
    return parseSolrRange(rangeStr, ctx);
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
function convertArbitraryRangeFacet(def: JavaMap, ranges: any, ctx: RequestContext): JavaMap {
  const rangeInner = new Map<string, any>();
  rangeInner.set('field', def.get('field'));

  // ranges can be an array of objects with {range: "[0,20)"} or {from: 0, to: 20}
  const rangeItems = Array.isArray(ranges) ? ranges : Array.from(ranges as Iterable<any>);
  const osRanges: JavaMap[] = [];

  for (const item of rangeItems) {
    const converted = convertRangeItem(item, ctx);
    if (converted) {
      osRanges.push(converted);
    }
  }

  rangeInner.set('ranges', osRanges);

  const knownKeys = new Set(['type', 'field', 'ranges', 'mincount', 'hardend', 'include', 'other', 'facet']);
  warnUnknownKeys(def, knownKeys, 'arbitrary range');

  return new Map<string, any>([['range', rangeInner]]);
}

/**
 * Determine whether a uniform range facet targets a date or numeric field.
 *
 * Current strategy: inspect the `gap` value for Solr date-math patterns
 * (e.g. "+1MONTH", "+5MINUTES").
 *
 * Future: this function can be extended to accept explicit schema / field-type
 * metadata when that support becomes available.
 */
type FieldTypeHint = 'numeric' | 'date';

function detectFieldType(def: JavaMap): FieldTypeHint {
  const gap = def.get('gap');
  if (gap != null && typeof gap === 'string' && isSolrDateMathGap(gap)) {
    return 'date';
  }
  return 'numeric';
}

/**
 * Convert Solr uniform range (start/end/gap) to the appropriate OpenSearch
 * aggregation — either `histogram` (numeric) or `date_histogram` (date).
 */
function convertUniformRangeFacet(def: JavaMap, ctx: RequestContext): JavaMap {
  const fieldType = detectFieldType(def);
  if (fieldType === 'date') {
    return convertDateHistogramFacet(def, ctx);
  }
  return convertNumericHistogramFacet(def);
}

/**
 * Convert Solr uniform range (start/end/gap) to an OpenSearch `histogram` aggregation
 * for numeric fields.
 */
function convertNumericHistogramFacet(def: JavaMap): JavaMap {
  const histogramInner = new Map<string, any>();
  histogramInner.set('field', def.get('field'));

  const gap = def.get('gap');
  if (gap != null) histogramInner.set('interval', gap);

  const start = def.get('start');
  const end = def.get('end');

  // Solr's start/end define the bucket-generation window:
  //   - No buckets are created outside [start, end)  → hard_bounds
  //   - Empty buckets ARE created within [start, end) → extended_bounds
  // In OpenSearch we need both to replicate this behaviour.
  if (start != null || end != null) {
    const extBounds = new Map<string, any>();
    const hardBounds = new Map<string, any>();
    if (start != null) {
      extBounds.set('min', start);
      hardBounds.set('min', start);
    }
    // Solr's end is exclusive — the last bucket starts at (end - gap).
    // extended_bounds.max means "ensure a bucket containing this value exists",
    // so we use (end - gap) to avoid creating an extra bucket at `end`.
    if (end != null && gap != null) {
      extBounds.set('max', end - gap);
      hardBounds.set('max', end);
    } else if (end != null) {
      extBounds.set('max', end);
      hardBounds.set('max', end);
    }
    histogramInner.set('extended_bounds', extBounds);
    histogramInner.set('hard_bounds', hardBounds);
  }

  const mincount = def.get('mincount');
  if (mincount != null) histogramInner.set('min_doc_count', mincount);

  warnUnsupportedRangeParams(def);
  warnUnknownRangeKeys(def);

  return new Map<string, any>([['histogram', histogramInner]]);
}

/**
 * Convert Solr uniform range (start/end/gap) to an OpenSearch `date_histogram`
 * aggregation for date fields.
 *
 * Unlike the numeric path, date extended_bounds pass through the raw start/end
 * strings (no arithmetic), and the gap is translated via convertSolrDateGap().
 */
function convertDateHistogramFacet(def: JavaMap, ctx: RequestContext): JavaMap {
  const dateHistInner = new Map<string, any>();
  dateHistInner.set('field', def.get('field'));

  const gap = def.get('gap');
  if (gap != null) {
    const interval = convertSolrDateGap(gap);
    dateHistInner.set(interval.type, interval.value);
    if (interval.approximation) {
      ctx.emitMetric(interval.approximation === 'compound'
        ? 'date_range_gap_compound'
        : 'date_range_gap');
    }
  }

  // Return ISO-8601 date strings (like Solr) instead of epoch millis
  dateHistInner.set('format', 'strict_date_time_no_millis');

  const start = def.get('start');
  const end = def.get('end');

  // Solr's start/end define the bucket-generation window:
  //   - No buckets are created outside [start, end)  → hard_bounds
  //   - Empty buckets ARE created within [start, end) → extended_bounds
  // In OpenSearch we need both to replicate this behaviour.
  //
  // Solr's `end` is exclusive — no bucket starts AT `end`.
  // For extended_bounds.max we subtract 1 s so the last *forced*
  // bucket is the one just before `end`, matching Solr's semantics.
  // hard_bounds.max keeps the original `end` to clip any overshoot.
  if (start != null || end != null) {
    const extBounds = new Map<string, any>();
    const hardBounds = new Map<string, any>();
    if (start != null) {
      extBounds.set('min', start);
      hardBounds.set('min', start);
    }
    if (end != null) {
      // Subtract 1 second so the max falls in the last valid bucket
      // (not at the exclusive `end` boundary).  Format must match
      // strict_date_time_no_millis ("yyyy-MM-dd'T'HH:mm:ssZ").
      const endMs = new Date(end as string).getTime();
      const maxDate = new Date(endMs - 1000);
      extBounds.set('max', maxDate.toISOString().replace(/\.\d{3}Z$/, 'Z'));
      hardBounds.set('max', end);
    }
    dateHistInner.set('extended_bounds', extBounds);
    dateHistInner.set('hard_bounds', hardBounds);
  }

  const mincount = def.get('mincount');
  if (mincount != null) dateHistInner.set('min_doc_count', mincount);

  warnUnsupportedRangeParams(def);
  warnUnknownRangeKeys(def);

  return new Map<string, any>([['date_histogram', dateHistInner]]);
}

/** Warn about Solr range parameters that have no direct OpenSearch histogram equivalent. */
function warnUnsupportedRangeParams(def: JavaMap): void {
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
}

/** Warn about unknown keys in a uniform range facet definition. */
function warnUnknownRangeKeys(def: JavaMap): void {
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
    'facet',
  ]);
  warnUnknownKeys(def, knownKeys, 'range');
}

// endregion

// region Query facet

/**
 * Convert a Solr query facet to an OpenSearch `filter` aggregation.
 *
 * Solr query facet:
 *   { "type": "query", "q": "popularity:[100 TO *]" }
 *
 * OpenSearch filter aggregation:
 *   { "filter": { "query_string": { "query": "popularity:[100 TO *]" } } }
 *
 * The `q` parameter is converted as follows:
 *   - `*:*` (or absent) → `match_all`
 *   - Everything else → `query_string` passthrough, which lets OpenSearch
 *     parse the full Lucene query syntax (ranges, booleans, wildcards, etc.)
 */
function convertQueryFacet(def: JavaMap): JavaMap {
  const q = (def.get('q') || '*:*').toString();
  const query: JavaMap = (!q || q === '*:*')
    ? new Map([['match_all', new Map()]])
    : new Map([['query_string', new Map([['query', q]])]]);

  const knownKeys = new Set(['type', 'q', 'facet']);
  warnUnknownKeys(def, knownKeys, 'query');

  return new Map<string, any>([['filter', query]]);
}

// endregion

// region Stat (metric) facets

/**
 * Solr stat function → OpenSearch metric aggregation mapping.
 *
 * Solr's json.facet allows shorthand stat facets as plain strings:
 *   "avg_price": "avg(price)"
 * These map to OpenSearch metric aggregations:
 *   "avg_price": { "avg": { "field": "price" } }
 */
const STAT_FUNC_MAP: Record<string, string> = {
  avg: 'avg',
  sum: 'sum',
  min: 'min',
  max: 'max',
  unique: 'cardinality',
  hll: 'cardinality',
  countvals: 'value_count',
};

/**
 * Convert a Solr stat facet string (e.g., "avg(price)") to an OpenSearch metric agg.
 */
function convertStatFacet(expr: string): JavaMap {
  const func = parseFuncQuery(expr);
  return convertFuncToMetricAgg(func);
}

function convertFuncToMetricAgg(func: FuncNode): JavaMap {
  const osAggType = STAT_FUNC_MAP[func.name];
  if (!osAggType) {
    throw new Error(`Unsupported stat function '${func.name}' in json.facet`);
  }

  if (func.args.length !== 1) {
    throw new Error(`Stat function '${func.name}' expects exactly 1 argument, got ${func.args.length}`);
  }

  const arg = func.args[0];
  if (!('kind' in arg) || arg.kind !== 'field') {
    throw new Error(`Stat function '${func.name}' expects a field reference argument`);
  }

  const inner = new Map<string, any>();
  inner.set('field', arg.name);
  return new Map<string, any>([[osAggType, inner]]);
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
 *
 * If the definition contains a `facet` key with nested sub-facets,
 * they are recursively converted and attached as a sibling `aggs` key
 * on the returned aggregation Map.
 */
function convertSingleFacet(facetDef: any, ctx: RequestContext): JavaMap {
  if (!isMapLike(facetDef)) {
    return new Map();
  }

  const type = (facetDef.get('type') || '').toString().toLowerCase();

  let result: JavaMap;
  switch (type) {
    case 'terms':
      result = convertTermsFacet(facetDef, ctx);
      break;
    case 'range':
      result = convertRangeFacet(facetDef, ctx);
      break;
    case 'query':
      result = convertQueryFacet(facetDef);
      break;
    default:
      throw new Error(`Facet type '${type}' is not implemented`);
  }

  // Handle nested sub-facets: Solr's `facet` key → OpenSearch's `aggs` key
  const subFacets = facetDef.get('facet');
  if (subFacets && isMapLike(subFacets) && subFacets.size > 0) {
    result.set('aggs', convertJsonFacets(subFacets, ctx));
  }

  return result;
}

/**
 * Convert an entire Solr json.facet object to an OpenSearch aggs object.
 *
 * @param solrJsonFacet - The Solr json.facet Map (keys are facet names, values are facet definitions)
 * @returns An OpenSearch aggs Map ready to set on the request body
 */
export function convertJsonFacets(solrJsonFacet: JavaMap, ctx: RequestContext): JavaMap {
  const aggs = new Map<string, any>();
  for (const name of solrJsonFacet.keys()) {
    const facetDef = solrJsonFacet.get(name);
    if (typeof facetDef === 'string') {
      aggs.set(name, convertStatFacet(facetDef));
    } else {
      aggs.set(name, convertSingleFacet(facetDef, ctx));
    }
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
      ctx.body.set('aggs', convertJsonFacets(facetMap, ctx));
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
