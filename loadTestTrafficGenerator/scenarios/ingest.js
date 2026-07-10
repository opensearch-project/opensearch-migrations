/**
 * Ingest scenario — Phases 1 & 2 (Profile 1)
 *
 * Phase 1 (SEQUENCE_FRACTION=0): 70% _bulk, 30% single-doc writes.
 * Phase 2 (SEQUENCE_FRACTION>0): remaining budget split 70/30 between bulk and single-doc.
 *   Default: 15% stateful sequence, 59.5% bulk, 25.5% single-doc.
 *
 * Key environment variables (see k6-config/ingest-steady.env for defaults):
 *   CAPTURE_PROXY_URL  — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME         — target OpenSearch index
 *   INGEST_RATE        — target requests/second (arrival rate)
 *   INGEST_VUS         — pre-allocated VUs (= concurrent connections in pinned mode)
 *   INGEST_MAX_VUS     — max VUs k6 may spin up to meet the rate
 *   DURATION           — test duration (e.g. "5m", "30s")
 *   BULK_BATCH_SIZE    — documents per _bulk call
 *   SEQUENCE_FRACTION  — share of iterations run as a create→update→query→delete sequence
 *                        (0.0 = Phase 1 behavior; default 0.15)
 *   CONNECTION_MODE    — "pinned" (default, keep-alive) or "spread" (Connection: close)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { randomDocument, randomBulkBatch } from '../lib/documents.js';
import { runSequence } from '../lib/sequences.js';
import { pinned, spread } from '../lib/connection-control.js';

// ── Custom metrics ─────────────────────────────────────────────────────────
// k6 remote-write appends its own type suffix; names here must NOT include suffixes.
// Prometheus names: k6_ingest_bulk_requests_total, k6_ingest_sequence_requests_total, etc.
const bulkRequests     = new Counter('ingest_bulk_requests');
const singleRequests   = new Counter('ingest_single_doc_requests');
const sequenceRequests = new Counter('ingest_sequence_requests');
const ingestErrors     = new Rate('ingest_errors');
const sequenceErrors   = new Rate('ingest_sequence_errors');
const bulkBatchDocs    = new Trend('ingest_bulk_batch_docs');

// ── Config ─────────────────────────────────────────────────────────────────
const PROXY_URL       = __ENV.CAPTURE_PROXY_URL   || 'https://capture-proxy:9200';
const INDEX           = __ENV.INDEX_NAME          || 'nyc_taxis';
const RATE            = parseInt(__ENV.INGEST_RATE         || '50');
const VUS             = parseInt(__ENV.INGEST_VUS          || '20');
const MAX_VUS         = parseInt(__ENV.INGEST_MAX_VUS      || '100');
const DURATION        = __ENV.DURATION            || '5m';
const BATCH_SIZE      = parseInt(__ENV.BULK_BATCH_SIZE     || '20');
const SEQ_FRACTION    = parseFloat(__ENV.SEQUENCE_FRACTION || '0.15');
const CONNECTION_MODE = __ENV.CONNECTION_MODE     || 'pinned';

// ── Index mapping (read once in init context) ──────────────────────────────
const INDEX_MAPPING = open('../data/nyc_taxis_mapping.json');

// ── Connection params (resolved once per VU in init context) ───────────────
const connParams = CONNECTION_MODE === 'spread' ? spread() : pinned();

// ── k6 options ─────────────────────────────────────────────────────────────
export const options = {
  insecureSkipTLSVerify: true, // capture proxy uses a self-signed cert (generateSelfSignedCerts task)

  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: MAX_VUS,
    },
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
  const { success } = runSequence(PROXY_URL, INDEX, connParams);
  sequenceRequests.add(1);
  sequenceErrors.add(success ? 0 : 1);
  ingestErrors.add(success ? 0 : 1);
}

function sendBulk() {
  const { body, docCount } = randomBulkBatch(INDEX, BATCH_SIZE);

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
    JSON.stringify(randomDocument()),
    { ...connParams, tags: { name: 'single_doc' } },
  );

  singleRequests.add(1);
  ingestErrors.add(res.status >= 400 ? 1 : 0);

  check(res, {
    'single doc created (201)': (r) => r.status === 201,
  });
}
