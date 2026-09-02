/**
 * HTTP client for requests aimed at the cluster through the Capture Proxy.
 *
 * The proxy is a transparent forwarder, so each request must carry the source cluster's
 * authentication. Basic auth may still be supplied as visible per-run parameters. Credentials
 * supplied through an auth Secret use the K6_AUTH_* aliases so the chart's empty AUTH_* defaults
 * cannot overwrite them.
 *
 * SigV4 signs the source endpoint while sending to CAPTURE_PROXY_URL. The resulting Host header is
 * the source host, matching console_link's sigv4_signing_endpoint behavior.
 *
 * Secret environment:
 *   K6_AUTH_MODE                         basic | sigv4 | none
 *   K6_AUTH_USERNAME / K6_AUTH_PASSWORD Basic credentials
 *   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN
 *   AWS_REGION
 *   SIGV4_SERVICE                       Defaults to es
 *   SIGV4_SIGNING_ENDPOINT              The real source endpoint
 */

import http from 'k6/http';
import encoding from 'k6/encoding';
import { CFG } from './config.js';
import { createSigV4Signer } from './sigv4.js';

const USERNAME = __ENV.K6_AUTH_USERNAME || CFG.AUTH_USERNAME || '';
const PASSWORD = __ENV.K6_AUTH_PASSWORD || CFG.AUTH_PASSWORD || '';
const HAS_AWS_CREDENTIALS = Boolean(__ENV.AWS_ACCESS_KEY_ID && __ENV.AWS_SECRET_ACCESS_KEY);
const MODE = (
  __ENV.K6_AUTH_MODE ||
  CFG.AUTH_MODE ||
  (HAS_AWS_CREDENTIALS ? 'sigv4' : USERNAME ? 'basic' : 'none')
).toLowerCase();

export const AUTH_MODE = MODE;
export const AUTH_ENABLED = MODE !== 'none';

if (!['none', 'basic', 'sigv4'].includes(MODE)) {
  throw new Error(`Unsupported K6_AUTH_MODE/AUTH_MODE '${MODE}'; expected none, basic, or sigv4`);
}
if (MODE === 'basic' && !USERNAME) {
  throw new Error('Basic authentication requires K6_AUTH_USERNAME or AUTH_USERNAME');
}

const BASIC_AUTH_HEADER = MODE === 'basic'
  ? { Authorization: `Basic ${encoding.b64encode(`${USERNAME}:${PASSWORD}`)}` }
  : null;

let sigv4Signer = null;
if (MODE === 'sigv4') {
  sigv4Signer = createSigV4Signer({
    accessKeyId: __ENV.AWS_ACCESS_KEY_ID,
    secretAccessKey: __ENV.AWS_SECRET_ACCESS_KEY,
    sessionToken: __ENV.AWS_SESSION_TOKEN,
    region: __ENV.AWS_REGION,
    service: __ENV.SIGV4_SERVICE || 'es',
    endpoint: __ENV.SIGV4_SIGNING_ENDPOINT || '',
  });
}

function decodeQueryComponent(value) {
  return decodeURIComponent(value.replace(/\+/g, ' '));
}

function parseRequestUrl(url) {
  // k6 2.2.0 has no global URL class. Keep this parser deliberately limited to absolute HTTP(S)
  // URLs, which are the only values the cluster client accepts.
  const match = /^https?:\/\/[^/?#]+([^?#]*)?(?:\?([^#]*))?$/i.exec(url);
  if (!match) throw new Error(`Cannot SigV4-sign invalid HTTP(S) URL '${url}'`);

  const query = {};
  for (const pair of (match[2] || '').split('&')) {
    if (!pair) continue;
    const separator = pair.indexOf('=');
    const key = decodeQueryComponent(separator < 0 ? pair : pair.slice(0, separator));
    const value = decodeQueryComponent(separator < 0 ? '' : pair.slice(separator + 1));
    if (query[key] === undefined) {
      query[key] = value;
    } else if (Array.isArray(query[key])) {
      query[key].push(value);
    } else {
      query[key] = [query[key], value];
    }
  }
  return { path: match[1] || '/', query };
}

function withBasicAuth(params) {
  const p = params || {};
  return { ...p, headers: { ...(p.headers || {}), ...BASIC_AUTH_HEADER } };
}

function withSigV4Auth(method, url, body, params) {
  const p = params || {};
  const parsedUrl = parseRequestUrl(url);
  const signedHeaders = sigv4Signer.sign({
    method: method.toUpperCase(),
    path: parsedUrl.path,
    query: parsedUrl.query,
    body: body == null ? '' : body,
  });
  return { ...p, headers: { ...(p.headers || {}), ...signedHeaders } };
}

/** Return k6 request params with the configured source authentication applied. */
export function withAuth(method, url, body, params) {
  if (MODE === 'basic') return withBasicAuth(params);
  if (MODE === 'sigv4') return withSigV4Auth(method, url, body, params);
  return params;
}

export function get(url, params) {
  return http.get(url, withAuth('GET', url, null, params));
}
export function del(url, body, params) {
  return http.del(url, body, withAuth('DELETE', url, body, params));
}
export function post(url, body, params) {
  return http.post(url, body, withAuth('POST', url, body, params));
}
export function put(url, body, params) {
  return http.put(url, body, withAuth('PUT', url, body, params));
}
export function patch(url, body, params) {
  return http.patch(url, body, withAuth('PATCH', url, body, params));
}
export function request(method, url, body, params) {
  return http.request(method, url, body, withAuth(method, url, body, params));
}

export default { get, del, post, put, patch, request, withAuth, AUTH_MODE, AUTH_ENABLED };
