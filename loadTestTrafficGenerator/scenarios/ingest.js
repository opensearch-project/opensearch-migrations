/**
 * Ingest scenario — Phases 1, 2 & 5 (Profile 1)
 *
 * Phase 1 (SEQUENCE_FRACTION=0): 70% _bulk, 30% single-doc writes.
 * Phase 2 (SEQUENCE_FRACTION>0): remaining budget split 70/30 between bulk and single-doc.
 *   Default: 15% stateful sequence, 59.5% bulk, 25.5% single-doc.
 * Phase 5 (EXECUTOR=ramping-arrival-rate): ramp / burst load shapes via RAMP_STAGES.
 *
 * Key environment variables (see k6-config/ingest-steady.env for load-profile defaults):
 *   SCENARIO           — document schema to use: "nyc_taxis" (default) or "logs_data"
 *   CAPTURE_PROXY_URL  — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME         — target OpenSearch index; defaults to the value of SCENARIO
 *   INGEST_RATE        — target requests/second for constant-arrival-rate executor;
 *                        also used as stage target when RAMP_STAGES is not set
 *   INGEST_VUS         — pre-allocated VUs (= concurrent connections in pinned mode)
 *   INGEST_MAX_VUS     — max VUs k6 may spin up to meet the rate
 *   DURATION           — test duration for constant-arrival-rate executor;
 *                        ignored when EXECUTOR=ramping-arrival-rate (stages define duration)
 *   BULK_BATCH_SIZE    — documents per _bulk call
 *   SEQUENCE_FRACTION  — share of iterations run as a create→update→query→delete sequence
 *                        (0.0 = Phase 1 behavior; default 0.15)
 *   CONNECTION_MODE    — "pinned" (default, keep-alive) or "spread" (Connection: close)
 *   EXECUTOR           — "constant-arrival-rate" (default) or "ramping-arrival-rate"
 *   RAMP_STAGES        — JSON array of k6 stage objects when EXECUTOR=ramping-arrival-rate
 *                        e.g. '[{"duration":"2m","target":150},{"duration":"1m","target":0}]'
 *                        Omit to use a single hold-at-INGEST_RATE-for-DURATION stage.
 *   CONTROL_ENABLED    — "true" to enable mid-test pause/resume/rate control via Webdis;
 *                        defaults to "false" (no-op). See lib/control.js and DESIGN.md §11.
 *   CONTROL_CMD_KEY    — Redis key polled for control commands (default: "control_cmd")
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import * as nycTaxisDocs from '../lib/data/nyc_taxis/documents.js';
import * as logsDocs     from '../lib/data/logs_data/documents.js';
import { runSequence } from '../lib/sequences.js';
import { pinned, spread } from '../lib/connection-control.js';
import { checkControl } from '../lib/control.js';

// ── Custom metrics ─────────────────────────────────────────────────────────
// k6 remote-write appends its own type suffix; names here must NOT include suffixes.
// Prometheus names: k6_ingest_bulk_requests_total, k6_ingest_sequence_requests_total, etc.
const bulkRequests     = new Counter('ingest_bulk_requests');
const singleRequests   = new Counter('ingest_single_doc_requests');
const sequenceRequests = new Counter('ingest_sequence_requests');
const ingestErrors     = new Rate('ingest_errors');
const sequenceErrors   = new Rate('ingest_sequence_errors');
const bulkBatchDocs    = new Trend('ingest_bulk_batch_docs');

// ── Scenario selection ─────────────────────────────────────────────────────
// All open() calls must happen at init time — k6 does not allow deferred file reads.
const SCENARIO = __ENV.SCENARIO || 'nyc_taxis';
const docs     = SCENARIO === 'logs_data' ? logsDocs : nycTaxisDocs;
const docFns   = { randomDocument: docs.randomDocument, randomUpdateBody: docs.randomUpdateBody };

const MAPPINGS = {
  nyc_taxis: open('../data/nyc_taxis/mapping.json'),
  logs_data: open('../data/logs_data/mapping.json'),
};
const INDEX_MAPPING = MAPPINGS[SCENARIO] || MAPPINGS['nyc_taxis'];

// ── Config ─────────────────────────────────────────────────────────────────
const PROXY_URL       = __ENV.CAPTURE_PROXY_URL   || 'https://capture-proxy:9200';
const INDEX           = __ENV.INDEX_NAME          || SCENARIO;
const RATE            = parseInt(__ENV.INGEST_RATE         || '50');
const VUS             = parseInt(__ENV.INGEST_VUS          || '20');
const MAX_VUS         = parseInt(__ENV.INGEST_MAX_VUS      || '100');
const DURATION        = __ENV.DURATION            || '5m';
const BATCH_SIZE      = parseInt(__ENV.BULK_BATCH_SIZE     || '20');
const SEQ_FRACTION    = parseFloat(__ENV.SEQUENCE_FRACTION || '0.15');
const CONNECTION_MODE = __ENV.CONNECTION_MODE     || 'pinned';
const EXECUTOR        = __ENV.EXECUTOR            || 'constant-arrival-rate';
const RAMP_STAGES     = __ENV.RAMP_STAGES
  ? JSON.parse(__ENV.RAMP_STAGES)
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
      stages: RAMP_STAGES,
    }
  : {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: MAX_VUS,
    };

// ── k6 options ─────────────────────────────────────────────────────────────
export const options = {
  insecureSkipTLSVerify: true, // capture proxy uses a self-signed cert (generateSelfSignedCerts task)

  scenarios: {
    ingest: ingestScenario,
  },

  thresholds: {
    'http_req_failed':                       ['rate<0.05'],
    'ingest_errors':                         ['rate<0.05'],
    'ingest_sequence_errors':                ['rate<0.05'],
    'http_req_duration{name:bulk_write}':    ['p(95)<3000'],
    'http_req_duration{name:single_doc}':    ['p(95)<2000'],
    'http_req_duration{name:seq_create}':    ['p(95)<2000'],
    'http_req_duration{name:seq_update}':    ['p(95)<2000'],
    'http_req_duration{name:seq_query}':     ['p(95)<2000'],
    'http_req_duration{name:seq_delete}':    ['p(95)<2000'],
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
// Dispatch: SEQ_FRACTION → sequence; remaining budget → 70% bulk / 30% single-doc.
export default function () {
  if (!checkControl(RATE)) return;

  const r = Math.random();
  if (r < SEQ_FRACTION) {
    doSequence();
  } else if (r < SEQ_FRACTION + (1 - SEQ_FRACTION) * 0.7) {
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

function sendBulk() {
  const { body, docCount } = docs.randomBulkBatch(INDEX, BATCH_SIZE);

  const res = http.post(
    `${PROXY_URL}/_bulk`,
    body,
    { ...connParams, tags: { name: 'bulk_write' } },
  );

  bulkBatchDocs.add(docCount);
  bulkRequests.add(1);
  ingestErrors.add(res.status >= 400 ? 1 : 0);

  check(res, {
    'bulk status 200': (r) => r.status === 200,
    'bulk no item errors': (r) => {
      try { return JSON.parse(r.body).errors === false; } catch (_) { return false; }
    },
  });
}

function sendSingleDoc() {
  const res = http.post(
    `${PROXY_URL}/${INDEX}/_doc`,
    JSON.stringify(docs.randomDocument()),
    { ...connParams, tags: { name: 'single_doc' } },
  );

  singleRequests.add(1);
  ingestErrors.add(res.status >= 400 ? 1 : 0);

  check(res, {
    'single doc created (201)': (r) => r.status === 201,
  });
}
