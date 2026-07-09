/**
 * Phase 1 — Ingest baseline (Profile 1, no stateful sequences)
 *
 * Sends _bulk writes (70%) and single-doc POSTs (30%) to the Capture Proxy
 * at a constant arrival rate. Validates that traffic flows through the proxy,
 * lands on Kafka, and that metrics reach Prometheus via remote write.
 *
 * Run:
 *   docker-compose run --rm --env-file k6-config/ingest-steady.env k6 \
 *     run --out=experimental-prometheus-rw /scripts/scenarios/ingest.js
 *
 * Key environment variables (see k6-config/ingest-steady.env for defaults):
 *   CAPTURE_PROXY_URL  — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME         — target OpenSearch index
 *   INGEST_RATE        — target requests/second (arrival rate)
 *   INGEST_VUS         — pre-allocated VUs (= concurrent connections)
 *   INGEST_MAX_VUS     — max VUs k6 may spin up to meet the rate
 *   DURATION           — test duration (e.g. "5m", "30s")
 *   BULK_BATCH_SIZE    — documents per _bulk call
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { randomDocument, randomBulkBatch } from '../lib/documents.js';

// ── Custom metrics ─────────────────────────────────────────────────────────
// k6 remote-write appends its own type suffix to whatever name is given here:
//   Counter → _total, Rate → _rate, Trend stat → _p95 etc.
// So names here must NOT include type suffixes — k6 adds them.
// Prometheus names: k6_ingest_bulk_requests_total, k6_ingest_single_doc_requests_total,
//                   k6_ingest_errors_rate, k6_ingest_bulk_batch_docs_p95 etc.
const bulkRequests   = new Counter('ingest_bulk_requests');
const singleRequests = new Counter('ingest_single_doc_requests');
const ingestErrors   = new Rate('ingest_errors');
const bulkBatchDocs  = new Trend('ingest_bulk_batch_docs');

// ── Config ─────────────────────────────────────────────────────────────────
const PROXY_URL   = __ENV.CAPTURE_PROXY_URL  || 'https://capture-proxy:9200';
const INDEX       = __ENV.INDEX_NAME         || 'nyc_taxis';
const RATE        = parseInt(__ENV.INGEST_RATE     || '50');
const VUS         = parseInt(__ENV.INGEST_VUS      || '20');
const MAX_VUS     = parseInt(__ENV.INGEST_MAX_VUS  || '100');
const DURATION    = __ENV.DURATION           || '5m';
const BATCH_SIZE  = parseInt(__ENV.BULK_BATCH_SIZE || '20');

// ── Index mapping (read once in init context) ──────────────────────────────
const INDEX_MAPPING = open('../data/nyc_taxis_mapping.json');

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
    'http_req_failed':                     ['rate<0.05'],   // <5% HTTP errors overall
    'ingest_errors':                       ['rate<0.05'],   // same threshold via custom metric
    'http_req_duration{name:bulk_write}': ['p(95)<3000'],  // bulk p95 < 3s
    'http_req_duration{name:single_doc}': ['p(95)<2000'],  // single-doc p95 < 2s
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

// ── Shared HTTP params ─────────────────────────────────────────────────────
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// ── VU function: 70% bulk, 30% single-doc ─────────────────────────────────
export default function () {
  if (Math.random() < 0.7) {
    sendBulk();
  } else {
    sendSingleDoc();
  }
}

function sendBulk() {
  const { body, docCount } = randomBulkBatch(INDEX, BATCH_SIZE);

  const res = http.post(
    `${PROXY_URL}/_bulk`,
    body,
    { ...JSON_HEADERS, tags: { name: 'bulk_write' } },
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
    { ...JSON_HEADERS, tags: { name: 'single_doc' } },
  );

  singleRequests.add(1);
  ingestErrors.add(res.status >= 400 ? 1 : 0);

  check(res, {
    'single doc created (201)': (r) => r.status === 201,
  });
}
