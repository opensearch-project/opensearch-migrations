/**
 * Search scenario — Phase 3 (Profile 2)
 *
 * Default operation mix (DEEP_PAGING_ENABLED=false):
 *   60% flat _search (term / range / bool)
 *   20% aggregation query (date-histogram, terms)
 *   10% partial update (existing doc from seed set, sampled during setup)
 *   5%  single-doc write
 *   5%  deep paging (scroll or search_after — activated by DEEP_PAGING_ENABLED=true)
 *        When disabled, these iterations run as flat searches instead.
 *
 * Key environment variables (see k6-config/search-steady.env for defaults):
 *   CAPTURE_PROXY_URL     — HTTPS endpoint of the Capture Proxy
 *   INDEX_NAME            — target OpenSearch index
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
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { randomDocument } from '../lib/documents.js';
import { flatSearch, aggSearch, scrollSequence, searchAfterSequence } from '../lib/queries.js';
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

// ── Config ──────────────────────────────────────────────────────────────────
const PROXY_URL          = __ENV.CAPTURE_PROXY_URL      || 'https://capture-proxy:9200';
const INDEX              = __ENV.INDEX_NAME             || 'nyc_taxis';
const RATE               = parseInt(__ENV.SEARCH_RATE      || '50');
const VUS                = parseInt(__ENV.SEARCH_VUS       || '30');
const MAX_VUS            = parseInt(__ENV.SEARCH_MAX_VUS   || '150');
const DURATION           = __ENV.DURATION               || '5m';
const DEEP_PAGING        = (__ENV.DEEP_PAGING_ENABLED    || 'false') === 'true';
const PAGING_MODE        = __ENV.PAGING_MODE            || 'scroll';
const SCROLL_PAGES       = parseInt(__ENV.SCROLL_PAGES     || '3');
const SEARCH_AFTER_PAGES = parseInt(__ENV.SEARCH_AFTER_PAGES || '3');
const CONNECTION_MODE    = __ENV.CONNECTION_MODE        || 'pinned';

// ── Static data (loaded once in init context, shared across all VUs) ────────
const fieldValues    = JSON.parse(open('../data/field-value-sample.json'));
const INDEX_MAPPING  = open('../data/nyc_taxis_mapping.json');

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

// ── Setup: ensure the index exists with the correct mapping, then sample seed IDs ─────────────
// Aggregations on vendor_id, payment_type, trip_type require keyword field types.
// date_histogram on pickup_datetime requires a date field type.
// Without this step, auto-created dynamic mapping maps these as text, causing 400s on all aggs.
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

    // Verify key fields have the correct types for aggregations.
    // A stale index from a previous run may have vendor_id/payment_type mapped as 'text'
    // (via OpenSearch dynamic mapping), which causes all terms/date_histogram aggs to return 400.
    const mappingRes = http.get(`${indexUrl}/_mapping`, { tags: { name: 'setup_verify_mapping' } });
    if (mappingRes.status === 200) {
      try {
        const m = JSON.parse(mappingRes.body);
        const props = m[INDEX] && m[INDEX].mappings && m[INDEX].mappings.properties;
        const vendorIdType = props && props.vendor_id && props.vendor_id.type;
        if (vendorIdType && vendorIdType !== 'keyword') {
          const msg = `index '${INDEX}' has wrong mapping — vendor_id is '${vendorIdType}', need 'keyword'. ` +
            `Aggregation queries will all return 400. Fix: docker compose down -v then re-run.`;
          console.error(`setup: FATAL — ${msg}`);
          throw new Error(msg);
        }
      } catch (e) {
        if (e.message && e.message.includes('wrong mapping')) throw e;
        // ignore JSON parse errors from unexpected mapping shape
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
    console.warn('setup: seed the index first for realistic update traffic (see DESIGN.md §7.1)');
  } else {
    console.log(`setup: sampled ${seedDocIds.length} seed doc IDs for partial updates`);
  }

  return { seedDocIds: seedDocIds };
}

// ── VU function ─────────────────────────────────────────────────────────────
// Dispatch: 60% flat, 20% agg, 10% update, 5% write, 5% deep-paging (or flat).
export default function (data) {
  const seedDocIds = data.seedDocIds;
  const r = Math.random();

  if (r < 0.60) {
    doFlatSearch();
  } else if (r < 0.80) {
    doAggSearch();
  } else if (r < 0.90) {
    doPartialUpdate(seedDocIds);
  } else if (r < 0.95) {
    doSingleDocWrite();
  } else if (DEEP_PAGING) {
    doDeepPaging();
  } else {
    doFlatSearch(); // 5% fallback when deep paging is disabled
  }
}

function doFlatSearch() {
  const res = flatSearch(PROXY_URL, INDEX, fieldValues, connParams);
  flatRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doAggSearch() {
  const res = aggSearch(PROXY_URL, INDEX, connParams);
  aggRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doPartialUpdate(seedDocIds) {
  if (!seedDocIds || seedDocIds.length === 0) {
    // No seed docs available — fall back to a flat search
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
  updateRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doSingleDocWrite() {
  const res = http.post(
    `${PROXY_URL}/${INDEX}/_doc`,
    JSON.stringify(randomDocument()),
    { ...connParams, tags: { name: 'search_single_doc' } },
  );
  check(res, { 'single doc created (201)': (r) => r.status === 201 });
  writeRequests.add(1);
  searchErrors.add(res.status >= 400 ? 1 : 0);
}

function doDeepPaging() {
  if (PAGING_MODE === 'search_after') {
    const { success, pagesRead } = searchAfterSequence(
      PROXY_URL, INDEX, connParams, SEARCH_AFTER_PAGES,
    );
    searchAfterReqs.add(1);
    searchAfterPages.add(pagesRead);
    deepPagingErrors.add(success ? 0 : 1);
    searchErrors.add(success ? 0 : 1);
  } else {
    const { success, pagesRead } = scrollSequence(
      PROXY_URL, INDEX, connParams, SCROLL_PAGES,
    );
    scrollRequests.add(1);
    scrollPages.add(pagesRead);
    deepPagingErrors.add(success ? 0 : 1);
    searchErrors.add(success ? 0 : 1);
  }
}
