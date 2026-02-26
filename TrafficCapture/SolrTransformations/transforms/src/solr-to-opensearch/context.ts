/**
 * Parsed context layer — parse once, share across all micro-transforms.
 *
 * Expensive operations (URL parsing, body deserialization, endpoint detection)
 * happen exactly once per message. Every micro-transform receives the
 * pre-parsed context instead of the raw message.
 *
 * The Java shim passes LinkedHashMap objects directly to GraalVM JS via
 * allowMapAccess(true). All reads use .get() and writes use .put() to
 * operate directly on the underlying Java Map — zero serialization overhead.
 */

export type SolrEndpoint = 'select' | 'update' | 'admin' | 'schema' | 'config' | 'unknown';

/**
 * A Java Map passed through GraalVM polyglot with allowMapAccess(true).
 * Supports .get(), .put(), .containsKey(), .remove(), .size(), .keySet(), etc.
 */
export type JavaMap = any;

/** Parsed once from the raw request. Shared across all request micro-transforms. */
export interface RequestContext {
  msg: JavaMap;
  endpoint: SolrEndpoint;
  collection: string | undefined;
  params: URLSearchParams;
  body: Record<string, unknown>;
}

/** Parsed once from the bundled {request, response}. Shared across all response micro-transforms. */
export interface ResponseContext {
  request: JavaMap;
  response: JavaMap;
  endpoint: SolrEndpoint;
  collection: string | undefined;
  requestParams: URLSearchParams;
  responseBody: Record<string, unknown>;
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

/** Parse body from a Java Map payload (uses .get() for map access). */
function parseBody(payload: JavaMap): Record<string, unknown> {
  if (!payload) return {};
  const textBody = typeof payload.get === 'function'
    ? payload.get('inlinedTextBody')
    : payload?.inlinedTextBody;
  if (textBody) return JSON.parse(textBody);
  const jsonBody = typeof payload.get === 'function'
    ? payload.get('inlinedJsonBody')
    : payload?.inlinedJsonBody;
  if (jsonBody) return jsonBody as Record<string, unknown>;
  return {};
}

export function buildRequestContext(msg: JavaMap): RequestContext {
  const uri: string = msg.get('URI') || '';
  return {
    msg,
    endpoint: detectEndpoint(uri),
    collection: /\/solr\/([^/]+)\//.exec(uri)?.[1],
    params: parseParams(uri),
    body: parseBody(msg.get('payload')),
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
    responseBody: parseBody(response.get('payload')),
  };
}
