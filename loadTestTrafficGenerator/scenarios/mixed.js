/**
 * Mixed scenario — Phases 4 & 5 (Profile 3)
 *
 * Two independent k6 scenarios share one test run:
 *   mixed_ingest  — ingest stream (Profile 1 operation mix)
 *   mixed_search  — search stream (Profile 2 operation mix)
 *
 * Shared ID registry: ingest VUs write newly-created doc IDs to a Redis ring
 * buffer. CONSISTENCY_FRACTION of search iterations draw an ID from the ring
 * and issue a targeted GET to verify write-then-read consistency through the
 * Capture Proxy → Kafka pipeline.
 *
 * Key environment variables (see k6-config/mixed-steady.env for load-profile defaults):
 *   SCENARIO                — document schema to use: "nyc_taxis" (default) or "logs_data"
 *   CAPTURE_PROXY_URL       — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME              — target OpenSearch index; defaults to the value of SCENARIO
 *   INGEST_RATE             — target ingest requests/second (constant) or max stage target
 *   SEARCH_RATE             — target search requests/second (constant) or max stage target
 *   INGEST_VUS              — pre-allocated ingest VUs
 *   INGEST_MAX_VUS          — max ingest VUs k6 may spin up
 *   SEARCH_VUS              — pre-allocated search VUs
 *   SEARCH_MAX_VUS          — max search VUs k6 may spin up
 *   DURATION                — test duration for both streams; ignored when ramping (stages define it)
 *   BULK_BATCH_SIZE         — documents per _bulk call
 *   SEQUENCE_FRACTION       — fraction of ingest iterations run as create→update→query→delete
 *   CONSISTENCY_FRACTION    — fraction of search iterations that query a recently-ingested doc
 *   CONNECTION_MODE         — "pinned" (default, keep-alive) or "spread" (Connection: close)
 *   SEED_DOC_COUNT          — expected document count (informs seed sampling in setup)
 *   WEBDIS_URL              — Webdis HTTP-to-Redis proxy URL (default: http://webdis:7379)
 *   EXECUTOR                — "constant-arrival-rate" (default) or "ramping-arrival-rate"
 *   INGEST_RAMP_STAGES      — JSON stage array for the ingest stream when EXECUTOR=ramping-arrival-rate
 *                             e.g. '[{"duration":"2m","target":80},{"duration":"1m","target":0}]'
 *   SEARCH_RAMP_STAGES      — JSON stage array for the search stream when EXECUTOR=ramping-arrival-rate
 *   MIN_RING_FILL           — minimum number of IDs the ring must contain before search VUs start;
 *                             converted to a startTime delay using the estimated single-doc ID rate
 *                             (INGEST_RATE × (1−SEQUENCE_FRACTION) × 0.3); default 0 (no delay)
 *   CONTROL_ENABLED         — "true" to enable mid-test pause/resume/rate control via Webdis;
 *                             defaults to "false" (no-op). See lib/control.js and DESIGN.md §11.
 *   CONTROL_CMD_KEY         — Redis key polled for control commands (default: "control_cmd")
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import * as nycTaxisDocs    from '../lib/nyc_taxis/documents.js';
import * as logsDocs         from '../lib/logs_data/documents.js';
import * as nycTaxisQueries  from '../lib/nyc_taxis/queries.js';
import * as logsQueries      from '../lib/logs_data/queries.js';
import { runSequence } from '../lib/sequences.js';
import { pinned, spread } from '../lib/connection-control.js';
import { registryFlush, registryWrite, registryRead } from '../lib/id-registry.js';
import { checkControl } from '../lib/control.js';

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

// ── Scenario selection ──────────────────────────────────────────────────────
// All open() calls must happen at init time — k6 does not allow deferred file reads.
const SCENARIO = __ENV.SCENARIO || 'nyc_taxis';
const docs     = SCENARIO === 'logs_data' ? logsDocs    : nycTaxisDocs;
const queries  = SCENARIO === 'logs_data' ? logsQueries : nycTaxisQueries;
const docFns   = { randomDocument: docs.randomDocument, randomUpdateBody: docs.randomUpdateBody };

const FIELD_VALUES_MAP = {
  nyc_taxis: JSON.parse(open('../data/nyc_taxis/field-value-sample.json')),
  logs_data: JSON.parse(open('../data/logs_data/field-value-sample.json')),
};
const fieldValues = FIELD_VALUES_MAP[SCENARIO] || FIELD_VALUES_MAP['nyc_taxis'];

const MAPPINGS = {
  nyc_taxis: open('../data/nyc_taxis/mapping.json'),
  logs_data: open('../data/logs_data/mapping.json'),
};
const INDEX_MAPPING = MAPPINGS[SCENARIO] || MAPPINGS['nyc_taxis'];

// ── Config ──────────────────────────────────────────────────────────────────
const PROXY_URL            = __ENV.CAPTURE_PROXY_URL        || 'https://capture-proxy:9200';
const INDEX                = __ENV.INDEX_NAME               || SCENARIO;
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
const EXECUTOR             = __ENV.EXECUTOR                 || 'constant-arrival-rate';
const INGEST_RAMP_STAGES   = __ENV.INGEST_RAMP_STAGES
  ? JSON.parse(__ENV.INGEST_RAMP_STAGES)
  : [{ duration: DURATION, target: INGEST_RATE }];
const SEARCH_RAMP_STAGES   = __ENV.SEARCH_RAMP_STAGES
  ? JSON.parse(__ENV.SEARCH_RAMP_STAGES)
  : [{ duration: DURATION, target: SEARCH_RATE }];

// ── Ring fill delay ────────────────────────────────────────────────────────
// Single-doc writes (the only operations that register IDs to the ring) occur
// at INGEST_RATE × (1 − SEQ_FRACTION) × 0.3 per second. MIN_RING_FILL converts
// a desired ID count into an estimated startTime delay for the search scenario,
// so search VUs don't begin until the ring has enough entries to hit consistently.
const MIN_RING_FILL     = parseInt(__ENV.MIN_RING_FILL || '0');
const EST_ID_RATE       = INGEST_RATE * (1 - SEQ_FRACTION) * 0.3;
const RING_FILL_DELAY_S = MIN_RING_FILL > 0 && EST_ID_RATE > 0
  ? Math.ceil(MIN_RING_FILL / EST_ID_RATE)
  : 0;
const SEARCH_START_TIME = RING_FILL_DELAY_S > 0 ? `${RING_FILL_DELAY_S}s` : undefined;

// ── Connection params (resolved once in init context) ──────────────────────
const connParams = CONNECTION_MODE === 'spread' ? spread() : pinned();

// ── Scenario configs (built at init time from EXECUTOR env var) ────────────
const mixedIngestScenario = EXECUTOR === 'ramping-arrival-rate'
  ? {
      executor: 'ramping-arrival-rate',
      exec: 'ingestVU',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: INGEST_VUS,
      maxVUs: INGEST_MAX_VUS,
      stages: INGEST_RAMP_STAGES,
    }
  : {
      executor: 'constant-arrival-rate',
      exec: 'ingestVU',
      rate: INGEST_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: INGEST_VUS,
      maxVUs: INGEST_MAX_VUS,
    };

const mixedSearchScenario = EXECUTOR === 'ramping-arrival-rate'
  ? {
      executor: 'ramping-arrival-rate',
      exec: 'searchVU',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: SEARCH_VUS,
      maxVUs: SEARCH_MAX_VUS,
      stages: SEARCH_RAMP_STAGES,
      ...(SEARCH_START_TIME !== undefined ? { startTime: SEARCH_START_TIME } : {}),
    }
  : {
      executor: 'constant-arrival-rate',
      exec: 'searchVU',
      rate: SEARCH_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: SEARCH_VUS,
      maxVUs: SEARCH_MAX_VUS,
      ...(SEARCH_START_TIME !== undefined ? { startTime: SEARCH_START_TIME } : {}),
    };

// ── k6 options ─────────────────────────────────────────────────────────────
export const options = {
  insecureSkipTLSVerify: true, // capture proxy uses a self-signed cert

  scenarios: {
    mixed_ingest: mixedIngestScenario,
    mixed_search: mixedSearchScenario,
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
      console.log(`setup: created index ${INDEX} with ${SCENARIO} mapping`);
    }
  } else {
    console.log(`setup: index ${INDEX} already exists (status ${existing.status})`);

    // Guard against a stale index with dynamic (wrong) field types.
    const mappingRes = http.get(`${indexUrl}/_mapping`, { tags: { name: 'setup_verify_mapping' } });
    if (mappingRes.status === 200) {
      try {
        const m = JSON.parse(mappingRes.body);
        const props = m[INDEX] && m[INDEX].mappings && m[INDEX].mappings.properties;
        const { field, type } = docs.CRITICAL_MAPPING_CHECK;
        const actualType = props && props[field] && props[field].type;
        if (actualType && actualType !== type) {
          throw new Error(
            `index '${INDEX}' has wrong mapping — ${field} is '${actualType}', need '${type}'. ` +
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
  if (RING_FILL_DELAY_S > 0) {
    console.log(
      `setup: search VUs delayed by ${RING_FILL_DELAY_S}s ` +
      `(MIN_RING_FILL=${MIN_RING_FILL} IDs ÷ est. ${EST_ID_RATE.toFixed(1)} IDs/s from single-doc ingest)`
    );
  }

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
  if (!checkControl(INGEST_RATE)) return;

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
  if (!checkControl(SEARCH_RATE)) return;

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
  const { success } = runSequence(PROXY_URL, INDEX, connParams, docFns);
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
    JSON.stringify(docs.randomDocument()),
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
  const { body, docCount } = docs.randomBulkBatch(INDEX, BATCH_SIZE);
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
  const res = queries.flatSearch(PROXY_URL, INDEX, fieldValues, connParams);
  searchFlatRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doAggSearch() {
  const res = queries.aggSearch(PROXY_URL, INDEX, connParams);
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
    JSON.stringify({ doc: docs.randomUpdateBody() }),
    { ...connParams, tags: { name: 'search_update' } },
  );
  check(res, { 'partial update (200)': (r) => r.status === 200 });
  searchUpdateRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doSingleDocWrite() {
  const res = http.post(
    `${PROXY_URL}/${INDEX}/_doc`,
    JSON.stringify(docs.randomDocument()),
    { ...connParams, tags: { name: 'search_single_doc' } },
  );
  check(res, { 'single doc created (201)': (r) => r.status === 201 });
  searchWriteRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}
