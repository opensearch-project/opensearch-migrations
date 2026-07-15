/**
 * ID ring buffer via Webdis.
 *
 * Ingest VUs call registryWrite() after successfully creating a document so that
 * search VUs can retrieve a recently-written ID for targeted write-then-read queries.
 *
 * The ring is a Redis list (RING_KEY) capped at RING_CAP entries via LTRIM.
 * New IDs are pushed to the head; the oldest fall off the tail automatically.
 *
 * All functions are synchronous — they use k6's built-in http module against a
 * Webdis HTTP-to-Redis proxy, which exposes the full Redis command set over HTTP.
 * This avoids the need for the k6/x/redis extension and a custom k6 image.
 *
 * Webdis API: GET /COMMAND/arg1/arg2/... → {"COMMAND": result}
 *
 * Disabled by default (REGISTRY_ENABLED != "true"). When disabled all three
 * functions are no-ops: flush and write do nothing, read returns null (callers
 * already treat null as a cache miss and fall back to flat search). This allows
 * scenarios/mixed.js to run in environments without Redis/Webdis deployed (e.g.
 * Kubernetes) — the consistency fraction simply falls back to flat searches.
 */

import http from 'k6/http';

const ENABLED    = (__ENV.REGISTRY_ENABLED || 'false').toLowerCase() === 'true';
const WEBDIS_URL = __ENV.WEBDIS_URL  || 'http://webdis:7379';
const RING_KEY   = __ENV.REGISTRY_KEY || 'recent_ids';
const RING_CAP   = parseInt(__ENV.REGISTRY_CAP || '1000');

// Tags used on registry HTTP calls so they appear separately in Prometheus and
// are not confused with Capture Proxy traffic.
const WRITE_PARAMS = { tags: { name: 'registry_write' } };
const TRIM_PARAMS  = { tags: { name: 'registry_trim'  } };
const READ_PARAMS  = { tags: { name: 'registry_read'  } };
const FLUSH_PARAMS = { tags: { name: 'registry_flush' } };

/** Delete the ring key — call once in setup() to start from a clean slate. */
export function registryFlush() {
  if (!ENABLED) return;
  http.get(`${WEBDIS_URL}/DEL/${RING_KEY}`, FLUSH_PARAMS);
}

/**
 * Push docId to the head of the ring and trim the tail to RING_CAP.
 * Two HTTP calls: LPUSH then LTRIM.
 */
export function registryWrite(docId) {
  if (!ENABLED) return;
  http.get(`${WEBDIS_URL}/LPUSH/${RING_KEY}/${docId}`, WRITE_PARAMS);
  http.get(`${WEBDIS_URL}/LTRIM/${RING_KEY}/0/${RING_CAP - 1}`, TRIM_PARAMS);
}

/**
 * Return a random doc ID from the ring, or null if disabled, if the ring is
 * empty, or if Webdis is unreachable. Callers treat null as a cache miss and
 * fall back to a flat search — no special handling needed for the disabled case.
 *
 * Picks a random index in [0, RING_CAP). LINDEX returns null for out-of-range
 * indices, so during warmup (ring shorter than RING_CAP) some calls return null
 * without needing a separate LLEN round trip.
 */
export function registryRead() {
  if (!ENABLED) return null;
  const idx = Math.floor(Math.random() * RING_CAP);
  const res = http.get(`${WEBDIS_URL}/LINDEX/${RING_KEY}/${idx}`, READ_PARAMS);
  if (res.status !== 200) return null;
  const val = JSON.parse(res.body).LINDEX;
  return (val !== null && val !== undefined) ? val : null;
}
