/**
 * Parsed context layer — parse once, share across all micro-transforms.
 *
 * Expensive operations (URL parsing, body deserialization, endpoint detection)
 * happen exactly once per message. Every micro-transform receives the
 * pre-parsed context instead of the raw message.
 */
import type { HttpRequestMessage, HttpResponseMessage, Payload } from '../types';

export type SolrEndpoint = 'select' | 'update' | 'admin' | 'schema' | 'config' | 'unknown';

/** Parsed once from the raw request. Shared across all request micro-transforms. */
export interface RequestContext {
  msg: HttpRequestMessage;
  endpoint: SolrEndpoint;
  collection: string | undefined;
  params: URLSearchParams;
  body: Record<string, unknown>;
}

/** Parsed once from the bundled {request, response}. Shared across all response micro-transforms. */
export interface ResponseContext {
  request: HttpRequestMessage;
  response: HttpResponseMessage;
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

function parseBody(payload?: Payload): Record<string, unknown> {
  if (payload?.inlinedTextBody) return JSON.parse(payload.inlinedTextBody);
  if (payload?.inlinedJsonBody) return payload.inlinedJsonBody as Record<string, unknown>;
  return {};
}

export function buildRequestContext(msg: HttpRequestMessage): RequestContext {
  return {
    msg,
    endpoint: detectEndpoint(msg.URI),
    collection: /\/solr\/([^/]+)\//.exec(msg.URI)?.[1],
    params: parseParams(msg.URI),
    body: parseBody(msg.payload),
  };
}

export function buildResponseContext(
  request: HttpRequestMessage,
  response: HttpResponseMessage,
): ResponseContext {
  return {
    request,
    response,
    endpoint: detectEndpoint(request.URI),
    collection: /\/solr\/([^/]+)\//.exec(request.URI)?.[1],
    requestParams: parseParams(request.URI),
    responseBody: parseBody(response.payload),
  };
}
