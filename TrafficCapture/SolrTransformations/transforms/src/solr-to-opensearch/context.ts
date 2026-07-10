/**
 * Parsed context layer — parse once, share across all micro-transforms.
 *
 * The Java shim parses JSON bodies with Jackson into LinkedHashMaps and passes
 * them to GraalVM JS via allowMapAccess(true). All access uses .get()/.set()
 * on the underlying Java Maps — zero serialization in JavaScript.
 *
 * This matches the replayer/document transform pattern in backfill.
 */

import type { MetricsAccumulator, TransformMetricName } from './metrics';
import { createMetrics, incrementMetric } from './metrics';

export type SolrEndpoint = 'select' | 'update' | 'admin' | 'schema' | 'config' | 'unknown';

/**
 * A Java Map passed through GraalVM polyglot with allowMapAccess(true).
 * Supports the JS Map protocol: .get(), .set(), .has(), .delete(), .keys(), .entries(), .size.
 */
export interface JavaMap {
  get(key: string): any;
  set(key: string, value: any): void;
  has(key: string): boolean;
  delete(key: string): boolean;
  keys(): Iterable<string>;
  entries(): Iterable<[string, any]>;
  size: number;
}

/** Parsed once from the raw request. Shared across all request micro-transforms. */
export interface RequestContext {
  msg: JavaMap;
  endpoint: SolrEndpoint;
  collection: string | undefined;
  params: URLSearchParams;
  /** The request body as a Java Map — use .get()/.set() for access. */
  body: JavaMap;
  /** Target name — 'opensearch', 'solr', etc. Set by the shim proxy. */
  targetName?: string;
  /** Routing mode — 'single' or 'dual'. Set by the shim proxy. */
  mode?: string;
  /**
   * Solr requestHandler config (defaults/invariants/appends) from solrconfig.xml.
   * Injected from bindings at init, set per-context so transforms access it via ctx.
   * Uses Record (plain object) because GraalVM exposes Java Maps as JS objects via allowMapAccess.
   */
  solrConfig?: Record<string, { defaults?: Record<string, string>; invariants?: Record<string, string>; appends?: Record<string, string> }>;
  /** Record a limitation metric occurrence. */
  emitMetric(metric: TransformMetricName): void;
  /** Internal accumulator — use {@link emitMetric} instead. */
  readonly _metrics: MetricsAccumulator;
}

/** Parsed once from the bundled {request, response}. Shared across all response micro-transforms. */
export interface ResponseContext {
  request: JavaMap;
  response: JavaMap;
  endpoint: SolrEndpoint;
  collection: string | undefined;
  requestParams: URLSearchParams;
  /** The response body as a Java Map — use .get()/.set() for access. */
  responseBody: JavaMap;
  /** Target name — 'opensearch', 'solr', etc. Set by the shim proxy. */
  targetName?: string;
  /** Routing mode — 'single' or 'dual'. Set by the shim proxy. */
  mode?: string;
}

const ENDPOINT_PATTERNS: [RegExp, SolrEndpoint][] = [
  [/\/solr\/[^/]+\/select/, 'select'],
  [/\/solr\/[^/]+\/update/, 'update'],
  [/\/solr\/[^/]+\/admin/, 'admin'],
  [/\/solr\/[^/]+\/schema/, 'schema'],
  [/\/solr\/[^/]+\/config/, 'config'],
];

function detectEndpoint(uri: string): SolrEndpoint {
  for (const [re, ep] of ENDPOINT_PATTERNS) {
    if (re.test(uri)) return ep;
  }
  return 'unknown';
}

function parseParams(uri: string): URLSearchParams {
  const q = uri.indexOf('?');
  return new URLSearchParams(q >= 0 ? uri.slice(q + 1) : '');
}

/**
 * Extract body from a Java Map payload.
 *
 * Returns one of:
 *   - a JavaMap  (JSON object body — the common case)
 *   - a List-like  (top-level JSON array body — e.g. /update/json/docs bulk ingest,
 *                   matches replayer JsonAccumulator semantics where a top-level
 *                   array is a valid single top-level JSON value)
 *   - an empty Map (no payload, or non-JSON content)
 *
 * Transforms that care about the shape should use isMapLike / isJavaList guards.
 */
function getBodyMap(payload: JavaMap): JavaMap {
  if (!payload) return new Map();
  const jsonBody = payload.get('inlinedJsonBody');
  if (!jsonBody) return new Map();
  // Map-shaped (typical JSON object) — return as-is
  if (typeof jsonBody.get === 'function') return jsonBody;
  // Fallback: non-Map (e.g. raw string from malformed body) — return empty map
  return new Map();
}

export function buildRequestContext(msg: JavaMap): RequestContext {
  const uri: string = msg.get('URI') || '';
  const metrics = createMetrics();
  return {
    msg,
    endpoint: detectEndpoint(uri),
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    params: parseParams(uri),
    body: getBodyMap(msg.get('payload')),
    targetName: msg.get('_targetName') || 'opensearch',
    mode: msg.get('_mode') || 'single',
    emitMetric: (metric: TransformMetricName) => incrementMetric(metrics, metric),
    _metrics: metrics,
  };
}

export function buildResponseContext(request: JavaMap, response: JavaMap): ResponseContext {
  const uri: string = request.get('URI') || '';
  return {
    request,
    response,
    endpoint: detectEndpoint(uri),
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    requestParams: parseParams(uri),
    responseBody: getBodyMap(response.get('payload')),
    targetName: request.get('_targetName') || 'opensearch',
    mode: request.get('_mode') || 'single',
  };
}
