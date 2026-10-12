/**
 * ID ring buffer in Valkey.
 *
 * Ingest VUs call registryWrite() after successfully creating a document so that
 * search VUs can retrieve a recently-written ID for targeted write-then-read queries.
 *
 * The ring is a Valkey list (RING_KEY) capped at RING_CAP entries via LTRIM.
 * New IDs are pushed to the head; the oldest fall off the tail automatically.
 *
 * The load-test runner compiles k6/x/redis into k6 and uses it to speak RESP directly to Valkey.
 *
 * Disabled by default (REGISTRY_ENABLED != "true"). When disabled all three
 * functions are no-ops: flush and write do nothing, read returns null (callers
 * already treat null as a cache miss and fall back to flat search). This allows
 * scenarios/mixed.js to run in environments without Valkey deployed (e.g.
 * Kubernetes) — the consistency fraction simply falls back to flat searches.
 */

import { CFG } from './config.js';
import { valkeyCommand } from './valkey.js';

const ENABLED    = (CFG.REGISTRY_ENABLED || 'false').toLowerCase() === 'true';
const RING_KEY   = CFG.REGISTRY_KEY || 'recent_ids';
const RING_CAP   = parseInt(CFG.REGISTRY_CAP || '1000');

/** Delete the ring key — call once in setup() to start from a clean slate. */
export async function registryFlush() {
  if (!ENABLED) return;
  await valkeyCommand('DEL', RING_KEY);
}

/**
 * Push docId to the head of the ring and trim the tail to RING_CAP.
 */
export async function registryWrite(docId) {
  if (!ENABLED) return;
  await valkeyCommand('LPUSH', RING_KEY, docId);
  await valkeyCommand('LTRIM', RING_KEY, 0, RING_CAP - 1);
}

/**
 * Return a random doc ID from the ring, or null if disabled, if the ring is
 * empty, or if Valkey is unreachable. Callers treat null as a cache miss and
 * fall back to a flat search — no special handling needed for the disabled case.
 *
 * Picks a random index in [0, RING_CAP). LINDEX returns null for out-of-range
 * indices, so during warmup (ring shorter than RING_CAP) some calls return null
 * without needing a separate round trip.
 */
export async function registryRead() {
  if (!ENABLED) return null;
  const idx = Math.floor(Math.random() * RING_CAP);
  const val = await valkeyCommand('LINDEX', RING_KEY, idx);
  return (val !== null && val !== undefined) ? val : null;
}
