/**
 * Search scenario
 *
 * Default operation mix (configurable via SEARCH_*_FRACTION env vars):
 *   60% flat _search (term / range / bool)        SEARCH_FLAT_FRACTION
 *   20% aggregation query (date-histogram, terms)  SEARCH_AGG_FRACTION
 *   10% partial update (seed doc sampled in setup) SEARCH_UPDATE_FRACTION
 *   5%  single-doc write                           SEARCH_WRITE_FRACTION
 *   5%  deep paging (scroll or search_after)       remainder (activated by DEEP_PAGING_ENABLED=true)
 *        When deep paging is disabled the remainder falls back to flat search.
 *
 * Key environment variables (see k6-config/search-steady.env for load-profile defaults):
 *   SCENARIO              — document schema to use: "nyc_taxis" (default) or "logs_data"
 *   CAPTURE_PROXY_URL     — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME            — target OpenSearch index; defaults to the value of SCENARIO
 *   SEARCH_RATE           — target requests/second (arrival rate)
 *   SEARCH_VUS            — pre-allocated VUs
 *   SEARCH_MAX_VUS        — max VUs k6 may spin up to meet the rate
 *   DURATION              — test duration (e.g. "5m")
 *   SEED_DOC_COUNT        — expected document count (informational; used for setup sampling)
 *   DEEP_PAGING_ENABLED   — "true" to activate scroll / search_after steps (default false)
 *   PAGING_MODE           — "scroll" or "search_after" (default "scroll")
 *   SCROLL_PAGES          — max pages per scroll sequence (default 3)
 *   SEARCH_AFTER_PAGES    — max pages per search_after sequence (default 3)
 *   CONNECTION_MODE       — "pinned" (default) or "spread"
 *   SEARCH_FLAT_FRACTION   — fraction of iterations for flat _search (default 0.60)
 *   SEARCH_AGG_FRACTION    — fraction of iterations for aggregation queries (default 0.20)
 *   SEARCH_UPDATE_FRACTION — fraction of iterations for partial updates (default 0.10)
 *   SEARCH_WRITE_FRACTION  — fraction of iterations for single-doc writes (default 0.05;
 *                            remainder goes to deep paging / flat fallback)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import * as nycTaxisDocs    from '../lib/data/nyc_taxis/documents.js';
import * as logsDocs         from '../lib/data/logs_data/documents.js';
import * as nycTaxisQueries  from '../lib/data/nyc_taxis/queries.js';
import * as logsQueries      from '../lib/data/logs_data/queries.js';
import { pinned, spread } from '../lib/connection-control.js';

// ── Custom metrics ──────────────────────────────────────────────────────────
// k6 remote-write appends the type suffix; names here must NOT include suffixes.
// Prometheus names: k6_search_flat_requests_total, k6_search_errors_rate, etc.
const flatRequests       = new Counter('search_flat_requests');
const aggRequests        = new Counter('search_agg_requests');
const updateRequests     = new Counter('search_update_requests');
const writeRequests      = new Counter('search_write_requests');
const scrollRequests     = new Counter('search_scroll_sequences');
const scrollPages        = new Counter('search_scroll_pages');
const searchAfterReqs    = new Counter('search_after_sequences');
const searchAfterPages   = new Counter('search_after_pages');
const searchErrors       = new Rate('search_errors');
const deepPagingErrors   = new Rate('search_deep_paging_errors');

// ── Scenario selection ──────────────────────────────────────────────────────
// All open() calls must happen at init time — k6 does not allow deferred file reads.
const SCENARIO = __ENV.SCENARIO || 'nyc_taxis';
const docs     = SCENARIO === 'logs_data' ? logsDocs    : nycTaxisDocs;
const queries  = SCENARIO === 'logs_data' ? logsQueries : nycTaxisQueries;

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
const PROXY_URL          = __ENV.CAPTURE_PROXY_URL      || 'https://capture-proxy:9200';
const INDEX              = __ENV.INDEX_NAME             || SCENARIO;
const RATE               = parseInt(__ENV.SEARCH_RATE      || '50');
const VUS                = parseInt(__ENV.SEARCH_VUS       || '30');
const MAX_VUS            = parseInt(__ENV.SEARCH_MAX_VUS   || '150');
const DURATION           = __ENV.DURATION               || '5m';
const DEEP_PAGING        = (__ENV.DEEP_PAGING_ENABLED    || 'false') === 'true';
const PAGING_MODE        = __ENV.PAGING_MODE            || 'scroll';
const SCROLL_PAGES       = parseInt(__ENV.SCROLL_PAGES          || '3');
const SEARCH_AFTER_PAGES = parseInt(__ENV.SEARCH_AFTER_PAGES    || '3');
const CONNECTION_MODE    = __ENV.CONNECTION_MODE               || 'pinned';
const FLAT_FRACTION      = parseFloat(__ENV.SEARCH_FLAT_FRACTION   || '0.60');
const AGG_FRACTION       = parseFloat(__ENV.SEARCH_AGG_FRACTION    || '0.20');
const UPDATE_FRACTION    = parseFloat(__ENV.SEARCH_UPDATE_FRACTION || '0.10');
const WRITE_FRACTION     = parseFloat(__ENV.SEARCH_WRITE_FRACTION  || '0.05');

// Precomputed cumulative dispatch thresholds
const T_AGG    = FLAT_FRACTION;
const T_UPDATE = FLAT_FRACTION + AGG_FRACTION;
const T_WRITE  = FLAT_FRACTION + AGG_FRACTION + UPDATE_FRACTION;
const T_DEEP   = FLAT_FRACTION + AGG_FRACTION + UPDATE_FRACTION + WRITE_FRACTION;

// ── Connection params (resolved once per VU in init context) ────────────────
const connParams = CONNECTION_MODE === 'spread' ? spread() : pinned();

// ── k6 options ───────────────────────────────────────────────────────────────
export const options = {
  insecureSkipTLSVerify: true, // capture proxy uses a self-signed cert

  scenarios: {
    search: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: MAX_VUS,
    },
  },

  thresholds: {
    'http_req_failed':                             ['rate<0.05'],
    'search_errors':                               ['rate<0.05'],
    'search_deep_paging_errors':                   ['rate<0.05'],
    'http_req_duration{name:search_flat}':         ['p(95)<3000'],
    'http_req_duration{name:search_agg}':          ['p(95)<5000'],
    'http_req_duration{name:search_update}':       ['p(95)<2000'],
    'http_req_duration{name:search_single_doc}':   ['p(95)<2000'],
    'http_req_duration{name:scroll_open}':         ['p(95)<3000'],
    'http_req_duration{name:scroll_page}':         ['p(95)<3000'],
    'http_req_duration{name:scroll_close}':        ['p(95)<2000'],
    'http_req_duration{name:search_after_page}':   ['p(95)<3000'],
  },
};

// ── Setup: ensure the index exists with the correct mapping, then sample seed IDs ──
// Aggregations on keyword fields require the correct field type.
// Without this step, auto-created dynamic mapping may map keyword fields as text,
// causing 400s on all agg queries.
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
          const msg = `index '${INDEX}' has wrong mapping — ${field} is '${actualType}', need '${type}'. ` +
            `Aggregation queries will all return 400. Fix: docker compose down -v then re-run.`;
          console.error(`setup: FATAL — ${msg}`);
          throw new Error(msg);
        }
      } catch (e) {
        if (e.message && e.message.includes('wrong mapping')) throw e;
      }
    }
  }

  // Sample up to 100 existing doc IDs for partial update operations.
  // Empty index is fine — doPartialUpdate falls back to flat search.
  const sampleRes = http.post(
    indexUrl + '/_search',
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
    console.warn('setup: no seed documents found — partial updates will fall back to flat searches');
    console.warn('setup: seed the index first for realistic update traffic');
  } else {
    console.log(`setup: sampled ${seedDocIds.length} seed doc IDs for partial updates`);
  }

  return { seedDocIds: seedDocIds };
}

// ── VU function ─────────────────────────────────────────────────────────────
// Dispatch thresholds derived from SEARCH_*_FRACTION env vars (see Config section above).
export default function (data) {
  const seedDocIds = data.seedDocIds;
  const r = Math.random();

  if (r < T_AGG) {
    doFlatSearch();
  } else if (r < T_UPDATE) {
    doAggSearch();
  } else if (r < T_WRITE) {
    doPartialUpdate(seedDocIds);
  } else if (r < T_DEEP) {
    doSingleDocWrite();
  } else if (DEEP_PAGING) {
    doDeepPaging();
  } else {
    doFlatSearch(); // remainder fallback when deep paging is disabled
  }
}

function doFlatSearch() {
  const res = queries.flatSearch(PROXY_URL, INDEX, fieldValues, connParams);
  flatRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doAggSearch() {
  const res = queries.aggSearch(PROXY_URL, INDEX, connParams);
  aggRequests.add(1);
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
  updateRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doSingleDocWrite() {
  const res = http.post(
    `${PROXY_URL}/${INDEX}/_doc`,
    JSON.stringify(docs.randomDocument()),
    { ...connParams, tags: { name: 'search_single_doc' } },
  );
  check(res, { 'single doc created (201)': (r) => r.status === 201 });
  writeRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doDeepPaging() {
  if (PAGING_MODE === 'search_after') {
    const { success, pagesRead } = queries.searchAfterSequence(
      PROXY_URL, INDEX, connParams, SEARCH_AFTER_PAGES,
    );
    searchAfterReqs.add(1);
    searchAfterPages.add(pagesRead);
    deepPagingErrors.add(success ? 0 : 1);
    searchErrors.add(success ? 0 : 1);
  } else {
    const { success, pagesRead } = queries.scrollSequence(
      PROXY_URL, INDEX, connParams, SCROLL_PAGES,
    );
    scrollRequests.add(1);
    scrollPages.add(pagesRead);
    deepPagingErrors.add(success ? 0 : 1);
    searchErrors.add(success ? 0 : 1);
  }
}
