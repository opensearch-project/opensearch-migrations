/**
 * Parsed context layer — parse once, share across all micro-transforms.
 *
 * The Java shim parses JSON bodies with Jackson into LinkedHashMaps and passes
 * them to GraalVM JS via allowMapAccess(true). All access uses .get()/.set()
 * on the underlying Java Maps — zero serialization in JavaScript.
 *
 * This matches the replayer/document transform pattern in backfill.
 */

export type SolrEndpoint = 'select' | 'update' | 'admin' | 'schema' | 'config' | 'unknown';

/**
 * A Java Map passed through GraalVM polyglot with allowMapAccess(true).
 * Supports .get(), .set(), .has(), .delete(), .keys(), .entries(), .size, etc.
 */
export type JavaMap = any;

/** Parsed once from the raw request. Shared across all request micro-transforms. */
export interface RequestContext {
  msg: JavaMap;
  endpoint: SolrEndpoint;
  collection: string | undefined;
  params: URLSearchParams;
  /** The request body as a Java Map — use .get()/.set() for access. */
  body: JavaMap;
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

/** Extract body Map from a Java Map payload. Returns the inlinedJsonBody Map directly — no parsing. */
function getBodyMap(payload: JavaMap): JavaMap {
  if (!payload) return new Map();
  const jsonBody = payload.get('inlinedJsonBody');
  if (jsonBody && typeof jsonBody.get === 'function') return jsonBody;
  // Fallback: if body is a string (non-JSON content), return empty map
  return new Map();
}

export function buildRequestContext(msg: JavaMap): RequestContext {
  const uri: string = msg.get('URI') || '';
  return {
    msg,
    endpoint: detectEndpoint(uri),
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    params: parseParams(uri),
    body: getBodyMap(msg.get('payload')),
  };
}

export function buildResponseContext(
  request: JavaMap,
  response: JavaMap,
): ResponseContext {
  const uri: string = request.get('URI') || '';
  return {
    request,
    response,
    endpoint: detectEndpoint(uri),
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    requestParams: parseParams(uri),
    responseBody: getBodyMap(response.get('payload')),
  };
}
