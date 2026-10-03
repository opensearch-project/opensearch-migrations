/**
 * Ingest scenario
 *
 * Operation mix (configurable):
 *   SEQUENCE_FRACTION of iterations → stateful create→update→query→delete sequence
 *   of the remaining budget: BULK_FRACTION → _bulk write; rest → single-doc POST
 *   Defaults: 15% sequence, 59.5% bulk, 25.5% single-doc.
 *   (EXECUTOR=ramping-arrival-rate): ramp / burst load shapes via RAMP_STAGES.
 *
 * Key environment variables (see k6-config/ingest-steady.env for load-profile defaults):
 *   SCHEMA           — document schema to use: "nyc_taxis" (default) or "logs_data"
 *   CAPTURE_PROXY_URL  — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME         — target OpenSearch index; defaults to the value of SCHEMA
 *   INGEST_RATE        — target requests/second for constant-arrival-rate executor;
 *                        also used as stage target when RAMP_STAGES is not set
 *   INGEST_VUS         — pre-allocated VUs (= concurrent connections in pinned mode)
 *   INGEST_MAX_VUS     — max VUs k6 may spin up to meet the rate
 *   DURATION           — test duration for constant-arrival-rate executor;
 *                        ignored when EXECUTOR=ramping-arrival-rate (stages define duration)
 *   BULK_BATCH_SIZE    — documents per _bulk call
 *   DOCUMENT_ID_PREFIX — namespace for replay-stable IDs from one distributed test run
 *   SEQUENCE_FRACTION  — share of iterations run as a create→update→query→delete sequence
 *                        (0.0 disables sequences; default 0.15)
 *   BULK_FRACTION      — share of non-sequence iterations sent as _bulk (default 0.70;
 *                        remainder goes to single-doc POSTs)
 *   CONNECTION_MODE    — "pinned" (default, keep-alive) or "spread" (Connection: close header)
 *   NO_CONNECTION_REUSE — "true" to disable keep-alive at the k6 transport level for all VUs
 *                         (noConnectionReuse: true); use alongside CONNECTION_MODE=spread for a
 *                         guaranteed per-request TCP teardown independent of server behaviour
 *   RETRY_ENABLED     — "true" to retry transient write failures with the same document IDs
 *   RETRY_WINDOW_SECONDS — maximum time spent retrying one logical request
 *   EXECUTOR           — "constant-arrival-rate" (default) or "ramping-arrival-rate"
 *   RAMP_STAGES        — JSON array of k6 stage objects when EXECUTOR=ramping-arrival-rate
 *                        e.g. '[{"duration":"2m","target":150},{"duration":"1m","target":0}]'
 *                        Omit to use a single hold-at-INGEST_RATE-for-DURATION stage.
 *   CONTROL_ENABLED    — "true" to enable mid-test pause/resume/rate control via Valkey;
 *                        defaults to "false" (no-op). See lib/control.js.
 *   CONTROL_CMD_KEY    — Valkey key polled for control commands (default: "control_cmd")
 */

import http from '../lib/http-client.js';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import * as nycTaxisDocs from '../lib/data/nyc_taxis/documents.js';
import * as logsDocs     from '../lib/data/logs_data/documents.js';
import { runSequence } from '../lib/sequences.js';
import { pinned, spread } from '../lib/connection-control.js';
import { checkControl } from '../lib/control.js';
import { CFG } from '../lib/config.js';

// ── Custom metrics ─────────────────────────────────────────────────────────
// k6 remote-write appends its own type suffix; names here must NOT include suffixes.
// Prometheus names: k6_ingest_bulk_requests_total, k6_ingest_sequence_requests_total, etc.
const bulkRequests     = new Counter('ingest_bulk_requests');
const singleRequests   = new Counter('ingest_single_doc_requests');
const sequenceRequests = new Counter('ingest_sequence_requests');
const ingestErrors     = new Rate('ingest_errors');
const retryAttempts    = new Counter('ingest_retry_attempts');
const sequenceErrors   = new Rate('ingest_sequence_errors');
const bulkBatchDocs    = new Trend('ingest_bulk_batch_docs');

// ── Schema selection ───────────────────────────────────────────────────────
const SCHEMA = CFG.SCHEMA || 'nyc_taxis';
const docs     = SCHEMA === 'logs_data' ? logsDocs : nycTaxisDocs;
const docFns   = { randomDocument: docs.randomDocument, randomUpdateBody: docs.randomUpdateBody };
const INDEX_MAPPING = docs.mapping;

// ── Config ─────────────────────────────────────────────────────────────────
const PROXY_URL       = CFG.CAPTURE_PROXY_URL   || 'https://capture-proxy:9201';
const INDEX           = CFG.INDEX_NAME          || SCHEMA;
const RATE            = parseInt(CFG.INGEST_RATE         || '50');
const VUS             = parseInt(CFG.INGEST_VUS          || '20');
const MAX_VUS         = parseInt(CFG.INGEST_MAX_VUS      || '100');
const DURATION        = CFG.DURATION            || '5m';
const BATCH_SIZE      = parseInt(CFG.BULK_BATCH_SIZE     || '20');
const DOCUMENT_ID_PREFIX = CFG.DOCUMENT_ID_PREFIX        || 'k6';
const SEQ_FRACTION    = parseFloat(CFG.SEQUENCE_FRACTION || '0.15');
const BULK_FRACTION   = parseFloat(CFG.BULK_FRACTION     || '0.70');
const RETRY_ENABLED   = (CFG.RETRY_ENABLED || 'false') === 'true';
const RETRY_WINDOW_SECONDS = parseInt(CFG.RETRY_WINDOW_SECONDS || '300');
const RETRY_ATTEMPT_TIMEOUT_SECONDS = parseInt(CFG.RETRY_ATTEMPT_TIMEOUT_SECONDS || '10');
const RETRY_BACKOFF_SECONDS = parseFloat(CFG.RETRY_BACKOFF_SECONDS || '1');
const GRACEFUL_STOP = CFG.GRACEFUL_STOP || '30s';
const HTTP_REQ_FAILED_THRESHOLD = CFG.HTTP_REQ_FAILED_THRESHOLD || 'rate<0.05';
const INGEST_ERROR_THRESHOLD = CFG.INGEST_ERROR_THRESHOLD || 'rate<0.05';
const DROPPED_ITERATIONS_THRESHOLD = CFG.DROPPED_ITERATIONS_THRESHOLD || 'count>=0';
const CONNECTION_MODE     = CFG.CONNECTION_MODE           || 'pinned';
const NO_CONNECTION_REUSE = (CFG.NO_CONNECTION_REUSE || 'false') === 'true';
const LATENCY_THRESHOLDS_ENABLED = (CFG.LATENCY_THRESHOLDS_ENABLED || 'true') === 'true';
const EXECUTOR            = CFG.EXECUTOR                 || 'constant-arrival-rate';
const RAMP_STAGES     = CFG.RAMP_STAGES
  ? JSON.parse(CFG.RAMP_STAGES)
  : [{ duration: DURATION, target: RATE }];

// ── Connection params (resolved once per VU in init context) ───────────────
const connParams = CONNECTION_MODE === 'spread' ? spread() : pinned();

// ── Scenario config (built at init time from EXECUTOR env var) ─────────────
const ingestScenario = EXECUTOR === 'ramping-arrival-rate'
  ? {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: VUS,
      maxVUs: MAX_VUS,
      gracefulStop: GRACEFUL_STOP,
      stages: RAMP_STAGES,
    }
  : {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: MAX_VUS,
      gracefulStop: GRACEFUL_STOP,
    };

// ── k6 options ─────────────────────────────────────────────────────────────
export const options = {
  insecureSkipTLSVerify: true, // capture proxy uses a self-signed cert (generateSelfSignedCerts task)
  ...(NO_CONNECTION_REUSE ? { noConnectionReuse: true } : {}),

  scenarios: {
    ingest: ingestScenario,
  },

  thresholds: {
    'http_req_failed':                       [HTTP_REQ_FAILED_THRESHOLD],
    'ingest_errors':                         [INGEST_ERROR_THRESHOLD],
    'ingest_sequence_errors':                ['rate<0.05'],
    'dropped_iterations':                    [DROPPED_ITERATIONS_THRESHOLD],
    ...(LATENCY_THRESHOLDS_ENABLED ? {
      'http_req_duration{name:bulk_write}':  ['p(95)<3000'],
      'http_req_duration{name:single_doc}':  ['p(95)<2000'],
      'http_req_duration{name:seq_create}':  ['p(95)<2000'],
      'http_req_duration{name:seq_update}':  ['p(95)<2000'],
      'http_req_duration{name:seq_query}':   ['p(95)<2000'],
      'http_req_duration{name:seq_delete}':  ['p(95)<2000'],
    } : {}),
  },
};

// ── Setup: ensure the index exists before VUs start ────────────────────────
export function setup() {
  const url = `${PROXY_URL}/${INDEX}`;

  const existing = http.get(url, { tags: { name: 'setup_check_index' } });
  if (existing.status === 404) {
    const res = http.put(url, INDEX_MAPPING, {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'setup_create_index' },
    });
    check(res, { 'index created (200)': (r) => r.status === 200 });
    if (res.status !== 200) {
      console.error(`Failed to create index: ${res.status} ${res.body}`);
    }
  } else {
    console.log(`Index ${INDEX} already exists (status ${existing.status}), skipping creation.`);
  }
}

// ── VU function ────────────────────────────────────────────────────────────
// Dispatch: SEQ_FRACTION → sequence; remaining budget → BULK_FRACTION bulk / rest single-doc.
export default async function () {
  if (!await checkControl(RATE)) return;

  const r = Math.random();
  if (r < SEQ_FRACTION) {
    doSequence();
  } else if (r < SEQ_FRACTION + (1 - SEQ_FRACTION) * BULK_FRACTION) {
    sendBulk();
  } else {
    sendSingleDoc();
  }
}

function doSequence() {
  const { success } = runSequence(PROXY_URL, INDEX, connParams, docFns);
  sequenceRequests.add(1);
  sequenceErrors.add(success ? 0 : 1);
  ingestErrors.add(success ? 0 : 1);
}

function stableId(kind, item = 0) {
  return `${DOCUMENT_ID_PREFIX}-${kind}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${item}`;
}

function bulkSucceeded(res) {
  if (res.status !== 200) return false;
  try { return JSON.parse(res.body).errors === false; } catch (_) { return false; }
}

function writeSucceeded(res) {
  return res.status === 200 || res.status === 201;
}

function isRetryable(res) {
  return res.status === 0 || res.status === 200 || res.status === 408 ||
    res.status === 429 || res.status >= 500;
}

function requestWithRetry(method, url, body, params, succeeded) {
  const deadline = Date.now() + RETRY_WINDOW_SECONDS * 1000;
  let res;
  while (true) {
    const remainingMs = deadline - Date.now();
    const requestParams = RETRY_ENABLED
      ? {
          ...params,
          timeout: `${Math.max(
            1,
            Math.min(RETRY_ATTEMPT_TIMEOUT_SECONDS * 1000, remainingMs),
          )}ms`,
        }
      : params;
    res = http.request(method, url, body, requestParams);
    if (succeeded(res) || !RETRY_ENABLED || !isRetryable(res) || Date.now() >= deadline) {
      return res;
    }
    retryAttempts.add(1);
    sleep(Math.min(RETRY_BACKOFF_SECONDS, Math.max(0, (deadline - Date.now()) / 1000)));
  }
}

function sendBulk() {
  const { body, docCount } = docs.randomBulkBatch(
    INDEX, BATCH_SIZE, (item) => stableId('bulk', item),
  );
  const res = requestWithRetry(
    'POST',
    `${PROXY_URL}/_bulk`,
    body,
    { ...connParams, tags: { name: 'bulk_write' } },
    bulkSucceeded,
  );
  const success = bulkSucceeded(res);

  bulkBatchDocs.add(docCount);
  bulkRequests.add(1);
  ingestErrors.add(success ? 0 : 1);

  check(res, {
    'bulk eventually succeeded': bulkSucceeded,
  });
}

function sendSingleDoc() {
  const body = JSON.stringify(docs.randomDocument());
  const res = requestWithRetry(
    'PUT',
    `${PROXY_URL}/${INDEX}/_doc/${stableId('single')}`,
    body,
    { ...connParams, tags: { name: 'single_doc' } },
    writeSucceeded,
  );
  const success = writeSucceeded(res);

  singleRequests.add(1);
  ingestErrors.add(success ? 0 : 1);

  check(res, {
    'single doc eventually written': writeSucceeded,
  });
}
