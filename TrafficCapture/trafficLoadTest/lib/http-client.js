/**
 * HTTP client for requests aimed at the cluster (through the Capture Proxy).
 *
 * The Capture Proxy is a transparent forwarder: it adds no credentials of its own, so whatever the
 * SOURCE cluster requires, the load test has to send. A cluster running with authentication — which
 * the testClusters chart does by default — answers an unauthenticated request with 401, and the run
 * then generates traffic but no data.
 *
 * Scenarios import this module INSTEAD of 'k6/http' so credentials are applied in one place rather
 * than at every call site. The wrapper only merges an Authorization header into the params object;
 * everything else (tags, connection mode, redirects, timeouts) passes through untouched, and the
 * return value is k6's own Response.
 *
 * Deliberately NOT used by lib/id-registry.js and lib/control.js: those talk to Webdis, not the
 * cluster, and must not be handed cluster credentials.
 *
 * Configuration (preset key or `-e` override; environment wins, as everywhere else):
 *   AUTH_USERNAME / AUTH_PASSWORD   HTTP Basic credentials. Auth is enabled by setting a username;
 *                                   leave unset for a cluster with security disabled.
 *   AUTH_MODE                       'basic' (default when AUTH_USERNAME is set) or 'none' to force
 *                                   credentials off without unsetting the username.
 */

import http from 'k6/http';
import encoding from 'k6/encoding';

import { CFG } from './config.js';

const USERNAME = CFG.AUTH_USERNAME || '';
const PASSWORD = CFG.AUTH_PASSWORD || '';
const MODE = (CFG.AUTH_MODE || (USERNAME ? 'basic' : 'none')).toLowerCase();

export const AUTH_ENABLED = MODE === 'basic' && USERNAME !== '';

/** Built once at init time — the credentials cannot change mid-run. */
const AUTH_HEADER = AUTH_ENABLED
  ? { Authorization: `Basic ${encoding.b64encode(`${USERNAME}:${PASSWORD}`)}` }
  : null;

/**
 * Merge the Authorization header into a params object without disturbing anything else. Returns the
 * original object when auth is off, so a run against an open cluster allocates nothing extra.
 */
export function withAuth(params) {
  if (!AUTH_HEADER) return params;
  const p = params || {};
  return { ...p, headers: { ...(p.headers || {}), ...AUTH_HEADER } };
}

// k6's http methods split into those that carry a body and those that do not; the params object is
// the last argument in both shapes.
export function get(url, params)          { return http.get(url, withAuth(params)); }
export function del(url, body, params)    { return http.del(url, body, withAuth(params)); }
export function post(url, body, params)   { return http.post(url, body, withAuth(params)); }
export function put(url, body, params)    { return http.put(url, body, withAuth(params)); }
export function patch(url, body, params)  { return http.patch(url, body, withAuth(params)); }
export function request(method, url, body, params) {
  return http.request(method, url, body, withAuth(params));
}

export default { get, del, post, put, patch, request, withAuth, AUTH_ENABLED };
