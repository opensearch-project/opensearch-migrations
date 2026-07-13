/**
 * Mixed scenario — Phase 4 (Profile 3)
 *
 * Two independent k6 scenarios share one test run:
 *   mixed_ingest  — constant-arrival-rate ingest (Profile 1 operation mix)
 *   mixed_search  — constant-arrival-rate search (Profile 2 operation mix)
 *
 * Shared ID registry: ingest VUs write newly-created doc IDs to a Redis ring
 * buffer. CONSISTENCY_FRACTION of search iterations draw an ID from the ring
 * and issue a targeted GET to verify write-then-read consistency through the
 * Capture Proxy → Kafka pipeline.
 *
 * Key environment variables (see k6-config/mixed-steady.env for defaults):
 *   CAPTURE_PROXY_URL       — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME              — target OpenSearch index
 *   INGEST_RATE             — target ingest requests/second
 *   SEARCH_RATE             — target search requests/second
 *   INGEST_VUS              — pre-allocated ingest VUs
 *   INGEST_MAX_VUS          — max ingest VUs k6 may spin up
 *   SEARCH_VUS              — pre-allocated search VUs
 *   SEARCH_MAX_VUS          — max search VUs k6 may spin up
 *   DURATION                — test duration for both streams (e.g. "5m")
 *   BULK_BATCH_SIZE         — documents per _bulk call
 *   SEQUENCE_FRACTION       — fraction of ingest iterations run as create→update→query→delete
 *   CONSISTENCY_FRACTION    — fraction of search iterations that query a recently-ingested doc
 *   CONNECTION_MODE         — "pinned" (default, keep-alive) or "spread" (Connection: close)
 *   SEED_DOC_COUNT          — expected document count (informs seed sampling in setup)
 *   WEBDIS_URL              — Webdis HTTP-to-Redis proxy URL (default: http://webdis:7379)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { randomDocument, randomBulkBatch } from '../lib/documents.js';
import { runSequence } from '../lib/sequences.js';
import { flatSearch, aggSearch } from '../lib/queries.js';
import { pinned, spread } from '../lib/connection-control.js';
import { registryFlush, registryWrite, registryRead } from '../lib/id-registry.js';

// ── Custom metrics ──────────────────────────────────────────────────────────
// k6 remote-write appends type suffixes; names here must NOT include them.
// Ingest stream
const ingestBulkRequests   = new Counter('mixed_ingest_bulk_requests');
const ingestSingleRequests = new Counter('mixed_ingest_single_requests');
const ingestSeqRequests    = new Counter('mixed_ingest_sequence_requests');
const ingestErrors         = new Rate('mixed_ingest_errors');
const ingestSeqErrors      = new Rate('mixed_ingest_sequence_errors');
const ingestBulkDocs       = new Trend('mixed_ingest_bulk_batch_docs');
// Search stream
const searchFlatRequests   = new Counter('mixed_search_flat_requests');
const searchAggRequests    = new Counter('mixed_search_agg_requests');
const searchUpdateRequests = new Counter('mixed_search_update_requests');
const searchWriteRequests  = new Counter('mixed_search_write_requests');
const consistencyReads     = new Counter('mixed_search_consistency_reads');
const consistencyMisses    = new Counter('mixed_search_consistency_misses');
const searchErrors         = new Rate('mixed_search_errors');
const idRegistryHits       = new Rate('mixed_id_registry_hits');

// ── Config ──────────────────────────────────────────────────────────────────
const PROXY_URL            = __ENV.CAPTURE_PROXY_URL        || 'https://capture-proxy:9200';
const INDEX                = __ENV.INDEX_NAME               || 'nyc_taxis';
const INGEST_RATE          = parseInt(__ENV.INGEST_RATE      || '30');
const SEARCH_RATE          = parseInt(__ENV.SEARCH_RATE      || '20');
const INGEST_VUS           = parseInt(__ENV.INGEST_VUS       || '15');
const INGEST_MAX_VUS       = parseInt(__ENV.INGEST_MAX_VUS   || '75');
const SEARCH_VUS           = parseInt(__ENV.SEARCH_VUS       || '15');
const SEARCH_MAX_VUS       = parseInt(__ENV.SEARCH_MAX_VUS   || '75');
const DURATION             = __ENV.DURATION                 || '5m';
const BATCH_SIZE           = parseInt(__ENV.BULK_BATCH_SIZE  || '20');
const SEQ_FRACTION         = parseFloat(__ENV.SEQUENCE_FRACTION     || '0.15');
const CONSISTENCY_FRACTION = parseFloat(__ENV.CONSISTENCY_FRACTION  || '0.10');
const CONNECTION_MODE      = __ENV.CONNECTION_MODE          || 'pinned';

// ── Static data (loaded once in init context) ──────────────────────────────
const fieldValues   = JSON.parse(open('../data/field-value-sample.json'));
const INDEX_MAPPING = open('../data/nyc_taxis_mapping.json');

// ── Connection params (resolved once in init context) ──────────────────────
const connParams = CONNECTION_MODE === 'spread' ? spread() : pinned();

// ── k6 options ─────────────────────────────────────────────────────────────
export const options = {
  insecureSkipTLSVerify: true, // capture proxy uses a self-signed cert

  scenarios: {
    mixed_ingest: {
      executor: 'constant-arrival-rate',
      exec: 'ingestVU',
      rate: INGEST_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: INGEST_VUS,
      maxVUs: INGEST_MAX_VUS,
    },
    mixed_search: {
      executor: 'constant-arrival-rate',
      exec: 'searchVU',
      rate: SEARCH_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: SEARCH_VUS,
      maxVUs: SEARCH_MAX_VUS,
    },
  },

  thresholds: {
    'http_req_failed':                              ['rate<0.05'],
    'mixed_ingest_errors':                          ['rate<0.05'],
    'mixed_ingest_sequence_errors':                 ['rate<0.05'],
    'mixed_search_errors':                          ['rate<0.05'],
    'http_req_duration{name:bulk_write}':           ['p(95)<3000'],
    'http_req_duration{name:single_doc}':           ['p(95)<2000'],
    'http_req_duration{name:seq_create}':           ['p(95)<2000'],
    'http_req_duration{name:seq_update}':           ['p(95)<2000'],
    'http_req_duration{name:seq_query}':            ['p(95)<2000'],
    'http_req_duration{name:seq_delete}':           ['p(95)<2000'],
    'http_req_duration{name:search_flat}':          ['p(95)<3000'],
    'http_req_duration{name:search_agg}':           ['p(95)<5000'],
    'http_req_duration{name:search_update}':        ['p(95)<2000'],
    'http_req_duration{name:consistency_read}':     ['p(95)<2000'],
  },
};

// ── Setup ───────────────────────────────────────────────────────────────────
// 1. Ensure the index exists with the correct mapping (same guard as search.js).
// 2. Flush the Redis ID ring so the test starts from a known-empty state.
// 3. Sample up to 100 seed doc IDs for the search stream's partial-update operation.
export function setup() {
  const indexUrl = `${PROXY_URL}/${INDEX}`;

  const existing = http.get(indexUrl, { tags: { name: 'setup_check_index' } });
  if (existing.status === 404) {
    const createRes = http.put(indexUrl, INDEX_MAPPING, {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'setup_create_index' },
    });
    check(createRes, { 'index created (200)': (r) => r.status === 200 });
    if (createRes.status !== 200) {
      console.error(`setup: failed to create index: ${createRes.status} ${createRes.body}`);
    } else {
      console.log(`setup: created index ${INDEX} with nyc_taxis mapping`);
    }
  } else {
    console.log(`setup: index ${INDEX} already exists (status ${existing.status})`);

    // Guard against a stale index with dynamic (wrong) mapping — same check as search.js.
    const mappingRes = http.get(`${indexUrl}/_mapping`, { tags: { name: 'setup_verify_mapping' } });
    if (mappingRes.status === 200) {
      try {
        const m = JSON.parse(mappingRes.body);
        const props = m[INDEX] && m[INDEX].mappings && m[INDEX].mappings.properties;
        const vendorIdType = props && props.vendor_id && props.vendor_id.type;
        if (vendorIdType && vendorIdType !== 'keyword') {
          throw new Error(
            `index '${INDEX}' has wrong mapping — vendor_id is '${vendorIdType}', need 'keyword'. ` +
            `Aggregation queries will return 400. Fix: docker compose down -v then re-run.`
          );
        }
      } catch (e) {
        if (e.message && e.message.includes('wrong mapping')) throw e;
      }
    }
  }

  // Flush the ID ring so the test does not draw from a previous run's IDs.
  registryFlush();
  console.log('setup: flushed ID ring (recent_ids)');

  // Sample seed doc IDs for the search stream's partial-update operation.
  const sampleRes = http.post(
    `${indexUrl}/_search`,
    JSON.stringify({ size: 100, query: { match_all: {} }, _source: false }),
    { ...connParams, tags: { name: 'setup_seed_sample' } },
  );

  const seedDocIds = [];
  if (sampleRes.status === 200) {
    const body = JSON.parse(sampleRes.body);
    if (body.hits && body.hits.hits) {
      for (let i = 0; i < body.hits.hits.length; i++) {
        seedDocIds.push(body.hits.hits[i]._id);
      }
    }
  }

  if (seedDocIds.length === 0) {
    console.warn('setup: no seed documents found — search updates will fall back to flat searches');
    console.warn('setup: seed the index with DataGenerator before running for realistic update traffic');
  } else {
    console.log(`setup: sampled ${seedDocIds.length} seed doc IDs for search-stream partial updates`);
  }

  return { seedDocIds };
}

// ── Ingest VU ───────────────────────────────────────────────────────────────
// Dispatch: SEQ_FRACTION → sequence (ephemeral, no registry)
//           remaining budget → 70% bulk / 30% single-doc with registry
export function ingestVU() {
  const r = Math.random();
  if (r < SEQ_FRACTION) {
    doSequence();
  } else if (r < SEQ_FRACTION + (1 - SEQ_FRACTION) * 0.7) {
    sendBulk();
  } else {
    doSingleDocWithRegistry();
  }
}

// ── Search VU ───────────────────────────────────────────────────────────────
// CONSISTENCY_FRACTION of iterations draw a recently-ingested ID from Redis.
// The remaining iterations run the standard Profile 2 mix.
export function searchVU(data) {
  const { seedDocIds } = data;

  if (Math.random() < CONSISTENCY_FRACTION) {
    doConsistencyRead();
    return;
  }

  const r = Math.random();
  if (r < 0.60) {
    doFlatSearch();
  } else if (r < 0.80) {
    doAggSearch();
  } else if (r < 0.90) {
    doPartialUpdate(seedDocIds);
  } else {
    doSingleDocWrite();
  }
}

// ── Ingest helpers ──────────────────────────────────────────────────────────

function doSequence() {
  // create → update → query → delete; doc is ephemeral so we do NOT register the ID.
  const { success } = runSequence(PROXY_URL, INDEX, connParams);
  ingestSeqRequests.add(1);
  ingestSeqErrors.add(success ? 0 : 1);
  ingestErrors.add(success ? 0 : 1);
}

function doSingleDocWithRegistry() {
  // Use an explicit PUT with a known ID so we can register it after a successful write.
  // mix-<VU>-<ITER> is unique per VU + iteration within the run.
  const id = `mix-${__VU}-${__ITER}`;
  const res = http.put(
    `${PROXY_URL}/${INDEX}/_doc/${id}`,
    JSON.stringify(randomDocument()),
    { ...connParams, tags: { name: 'single_doc' } },
  );
  ingestSingleRequests.add(1);
  ingestErrors.add(res.status >= 400 ? 1 : 0);
  check(res, { 'single doc written (200/201)': (r) => r.status === 200 || r.status === 201 });
  if (res.status === 200 || res.status === 201) {
    registryWrite(id);
  }
}

function sendBulk() {
  const { body, docCount } = randomBulkBatch(INDEX, BATCH_SIZE);
  const res = http.post(
    `${PROXY_URL}/_bulk`,
    body,
    { ...connParams, tags: { name: 'bulk_write' } },
  );
  ingestBulkDocs.add(docCount);
  ingestBulkRequests.add(1);
  ingestErrors.add(res.status >= 400 ? 1 : 0);
  check(res, {
    'bulk status 200': (r) => r.status === 200,
    'bulk no item errors': (r) => {
      try { return JSON.parse(r.body).errors === false; } catch (_) { return false; }
    },
  });
}

// ── Search helpers ──────────────────────────────────────────────────────────

function doConsistencyRead() {
  const docId = registryRead();
  const hit = docId !== null && docId !== undefined;
  idRegistryHits.add(hit ? 1 : 0);
  if (!hit) {
    // Ring is still empty during test warmup — fall back to a flat search.
    consistencyMisses.add(1);
    doFlatSearch();
    return;
  }
  const res = http.get(
    `${PROXY_URL}/${INDEX}/_doc/${docId}`,
    { ...connParams, tags: { name: 'consistency_read' } },
  );
  consistencyReads.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
  check(res, { 'consistency read (200)': (r) => r.status === 200 });
}

function doFlatSearch() {
  const res = flatSearch(PROXY_URL, INDEX, fieldValues, connParams);
  searchFlatRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doAggSearch() {
  const res = aggSearch(PROXY_URL, INDEX, connParams);
  searchAggRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doPartialUpdate(seedDocIds) {
  if (!seedDocIds || seedDocIds.length === 0) {
    doFlatSearch();
    return;
  }
  const id = seedDocIds[Math.floor(Math.random() * seedDocIds.length)];
  const res = http.post(
    `${PROXY_URL}/${INDEX}/_update/${id}`,
    JSON.stringify({ doc: { total_amount: parseFloat((Math.random() * 45 + 5).toFixed(2)) } }),
    { ...connParams, tags: { name: 'search_update' } },
  );
  check(res, { 'partial update (200)': (r) => r.status === 200 });
  searchUpdateRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doSingleDocWrite() {
  const res = http.post(
    `${PROXY_URL}/${INDEX}/_doc`,
    JSON.stringify(randomDocument()),
    { ...connParams, tags: { name: 'search_single_doc' } },
  );
  check(res, { 'single doc created (201)': (r) => r.status === 201 });
  searchWriteRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}
