/**
 * Index preparation shared by every scenario's setup().
 *
 * k6 counts any status outside 200-399 as a failed request, so the expected 404 and the 400 two
 * racing runners get from creating the same index are declared through responseCallback instead.
 * Only a successful lookup means the index exists; 0 and 5xx mean the endpoint is not serving yet.
 */

import http, { DEFAULT_OK_STATUSES, expectedStatuses } from './http-client.js';
import { sleep } from 'k6';

const PROBE_STATUSES = expectedStatuses(DEFAULT_OK_STATUSES, 404, { min: 500, max: 599 });
const CREATE_STATUSES = expectedStatuses(DEFAULT_OK_STATUSES, 400);
const HEALTH_STATUSES = expectedStatuses(DEFAULT_OK_STATUSES, 408);

const PROBE_ATTEMPTS = 6;
const PROBE_BACKOFF_SECONDS = 2;

/** True when the endpoint answered for itself; status 0 and 5xx mean it is not serving yet. */
export function settled(res) {
  return res.status >= 200 && res.status < 500;
}

/** True only for an affirmative lookup. A 401/403/429 is a refusal, not an existing index. */
export function lookupSucceeded(res) {
  return res.status >= 200 && res.status < 400;
}

function failed(res, reason) {
  return {
    ready: false,
    existed: false,
    created: false,
    writable: false,
    status: res.status,
    body: res.error || res.body,
    reason,
  };
}

function probeIndex(url) {
  let res;
  for (let attempt = 1; attempt <= PROBE_ATTEMPTS; attempt++) {
    res = http.get(url, {
      responseCallback: PROBE_STATUSES,
      tags: { name: 'setup_check_index' },
    });
    if (settled(res)) return res;
    if (attempt < PROBE_ATTEMPTS) sleep(PROBE_BACKOFF_SECONDS);
  }
  return res;
}

function alreadyExists(res) {
  if (res.status !== 400) return false;
  try {
    return JSON.parse(res.body).error.type === 'resource_already_exists_exception';
  } catch (_) {
    return false;
  }
}

/**
 * Ensure `index` exists with `mappingBody`, tolerating a concurrent setup() in another runner.
 * Returns { ready, existed, created, status, body, reason }.
 */
export function ensureIndexExists(proxyUrl, index, mappingBody) {
  const url = `${proxyUrl}/${index}`;

  const existing = probeIndex(url);
  if (!settled(existing)) {
    return failed(existing, `no answer from ${url} after ${PROBE_ATTEMPTS} attempts`);
  }
  if (lookupSucceeded(existing)) {
    return { ready: true, existed: true, created: false, status: existing.status };
  }
  if (existing.status !== 404) {
    return failed(existing, `lookup of '${index}' was refused`);
  }

  const created = http.put(url, mappingBody, {
    headers: { 'Content-Type': 'application/json' },
    responseCallback: CREATE_STATUSES,
    tags: { name: 'setup_create_index' },
  });
  if (created.status === 200) {
    return { ready: true, existed: false, created: true, status: created.status };
  }
  if (alreadyExists(created)) {
    // Another runner's setup() won the race.
    return { ready: true, existed: true, created: false, status: created.status };
  }
  return failed(created, `could not create index '${index}'`);
}

/**
 * Wait server-side until `index` has an active primary, so the first writes do not hit an
 * initializing shard. Returns { writable, status, reason }. The timeout is short because the
 * capture proxy records this request and a replayer would sit on it for its full duration.
 */
export function waitForIndexWritable(proxyUrl, index, timeoutSeconds = 30) {
  const res = http.get(
    `${proxyUrl}/_cluster/health/${index}?wait_for_status=yellow&timeout=${timeoutSeconds}s`,
    { responseCallback: HEALTH_STATUSES, tags: { name: 'setup_wait_index_writable' } },
  );
  if (res.status !== 200) {
    return {
      writable: false,
      status: res.status,
      reason: `health of '${index}' answered ${res.status}${res.error ? ` (${res.error})` : ''}`,
    };
  }
  try {
    if (JSON.parse(res.body).timed_out === false) {
      return { writable: true, status: res.status };
    }
  } catch (_) {
    return { writable: false, status: res.status, reason: `unreadable health body for '${index}'` };
  }
  return {
    writable: false,
    status: res.status,
    reason: `'${index}' did not reach yellow within ${timeoutSeconds}s`,
  };
}

/** ensureIndexExists followed by waitForIndexWritable. */
export function prepareIndex(proxyUrl, index, mappingBody, timeoutSeconds = 30) {
  const result = ensureIndexExists(proxyUrl, index, mappingBody);
  if (!result.ready) return result;

  const health = waitForIndexWritable(proxyUrl, index, timeoutSeconds);
  if (health.writable) return { ...result, writable: true };
  return { ...result, writable: false, status: health.status, reason: health.reason };
}

/** Abort setup unless the index is present and taking writes; a failed check would not. */
export function requireIndexReady(index, result) {
  if (result.ready && result.writable) return result;
  const detail = result.body ? `: ${result.body}` : '';
  throw new Error(
    `setup: index '${index}' is not ready for load — ` +
    `${result.reason || 'unknown cause'} (status ${result.status})${detail}`
  );
}
