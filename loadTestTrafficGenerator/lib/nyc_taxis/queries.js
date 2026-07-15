/**
 * Query helpers for the NYC Taxis scenario — Phase 3.
 *
 * Implements four query patterns against the NYC Taxis index:
 *   flatSearch          — term / range / bool _search, 10 hits
 *   aggSearch           — date-histogram and terms aggregations, size 0
 *   scrollSequence      — open scroll → fetch pages → always close context
 *   searchAfterSequence — stateless deep-paging via search_after + sort
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * fieldValues is the parsed data/nyc_taxis/field-value-sample.json object.
 */

import http from 'k6/http';
import { check } from 'k6';

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function pickRange(ranges) {
  return ranges[Math.floor(Math.random() * ranges.length)];
}

// ── Flat search ──────────────────────────────────────────────────────────────

function buildFlatBody(fieldValues) {
  const variant = Math.floor(Math.random() * 4);
  switch (variant) {
    case 0:
      return { size: 10, query: { term: { vendor_id: pick(fieldValues.vendor_ids) } } };
    case 1: {
      const r = pickRange(fieldValues.fare_amount.ranges);
      return { size: 10, query: { range: { fare_amount: { gte: r[0], lte: r[1] } } } };
    }
    case 2:
      return { size: 10, query: { term: { payment_type: pick(fieldValues.payment_types) } } };
    case 3: {
      const r = pickRange(fieldValues.trip_distance.ranges);
      return {
        size: 10,
        query: {
          bool: {
            filter: [
              { term: { vendor_id: pick(fieldValues.vendor_ids) } },
              { range: { trip_distance: { gte: r[0], lte: r[1] } } },
            ],
          },
        },
      };
    }
    default:
      return { size: 10, query: { match_all: {} } };
  }
}

export function flatSearch(proxyUrl, index, fieldValues, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_search`,
    JSON.stringify(buildFlatBody(fieldValues)),
    { ...connParams, tags: { name: 'search_flat' } },
  );
  check(res, { 'flat search (200)': (r) => r.status === 200 });
  return res;
}

// ── Aggregation search ───────────────────────────────────────────────────────

function buildAggBody() {
  const variant = Math.floor(Math.random() * 3);
  switch (variant) {
    case 0:
      return {
        size: 0,
        aggs: {
          trips_by_month: {
            date_histogram: {
              field: 'pickup_datetime',
              calendar_interval: 'month',
              format: 'yyyy-MM',
            },
          },
        },
      };
    case 1:
      return {
        size: 0,
        aggs: { by_payment_type: { terms: { field: 'payment_type', size: 10 } } },
      };
    case 2:
      return {
        size: 0,
        aggs: {
          by_vendor: {
            terms: { field: 'vendor_id', size: 10 },
            aggs: { avg_fare: { avg: { field: 'fare_amount' } } },
          },
        },
      };
    default:
      return { size: 0, aggs: { by_trip_type: { terms: { field: 'trip_type', size: 10 } } } };
  }
}

export function aggSearch(proxyUrl, index, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_search`,
    JSON.stringify(buildAggBody()),
    { ...connParams, tags: { name: 'search_agg' } },
  );
  check(res, { 'agg search (200)': (r) => r.status === 200 });
  return res;
}

// ── Scroll sequence ──────────────────────────────────────────────────────────

/**
 * Opens a scroll, fetches up to pageCount pages, then always closes the context.
 * Returns { success: boolean, pagesRead: number }.
 *
 * The scroll is closed in a finally block so leaked contexts cannot accumulate
 * even if a page fetch throws or the k6 script is interrupted.
 */
export function scrollSequence(proxyUrl, index, connParams, pageCount) {
  const openRes = http.post(
    `${proxyUrl}/${index}/_search?scroll=1m`,
    JSON.stringify({ size: 50, query: { match_all: {} } }),
    { ...connParams, tags: { name: 'scroll_open' } },
  );
  check(openRes, { 'scroll open (200)': (r) => r.status === 200 });

  if (openRes.status !== 200) {
    return { success: false, pagesRead: 0 };
  }

  let scrollId = null;
  let pagesRead = 1;
  let success = false;

  try {
    const openBody = JSON.parse(openRes.body);
    scrollId = openBody._scroll_id;

    const total = openBody.hits && openBody.hits.total
      ? (typeof openBody.hits.total === 'object' ? openBody.hits.total.value : openBody.hits.total)
      : 0;

    if (total > 0 && scrollId) {
      for (let page = 1; page < pageCount; page++) {
        const pageRes = http.post(
          `${proxyUrl}/_search/scroll`,
          JSON.stringify({ scroll: '1m', scroll_id: scrollId }),
          { ...connParams, tags: { name: 'scroll_page' } },
        );
        check(pageRes, { 'scroll page (200)': (r) => r.status === 200 });

        if (pageRes.status !== 200) break;

        const pageBody = JSON.parse(pageRes.body);
        if (pageBody._scroll_id) scrollId = pageBody._scroll_id;
        pagesRead++;

        const hits = pageBody.hits && pageBody.hits.hits ? pageBody.hits.hits : [];
        if (hits.length === 0) break;
      }
    }

    success = true;
  } finally {
    if (scrollId) {
      const closeRes = http.del(
        `${proxyUrl}/_search/scroll`,
        JSON.stringify({ scroll_id: scrollId }),
        { ...connParams, tags: { name: 'scroll_close' } },
      );
      // 200 = closed; 404 = already expired — both are acceptable (no leak either way)
      check(closeRes, { 'scroll close (ok)': (r) => r.status === 200 || r.status === 404 });
    }
  }

  return { success, pagesRead };
}

// ── search_after sequence ────────────────────────────────────────────────────

/**
 * Pages through results using search_after.
 * Each request is independent — no server-side state to clean up.
 * Stops when: a page has fewer hits than page size, hits are empty, or maxPages reached.
 * Returns { success: boolean, pagesRead: number }.
 */
export function searchAfterSequence(proxyUrl, index, connParams, maxPages) {
  let searchAfterKey = null;
  let pagesRead = 0;

  for (let page = 0; page < maxPages; page++) {
    const body = {
      size: 50,
      sort: [{ pickup_datetime: 'asc' }, { _id: 'asc' }],
      query: { match_all: {} },
    };
    if (searchAfterKey) {
      body.search_after = searchAfterKey;
    }

    const res = http.post(
      `${proxyUrl}/${index}/_search`,
      JSON.stringify(body),
      { ...connParams, tags: { name: 'search_after_page' } },
    );
    check(res, { 'search_after page (200)': (r) => r.status === 200 });

    if (res.status !== 200) break;

    const resBody = JSON.parse(res.body);
    const hits = resBody.hits && resBody.hits.hits ? resBody.hits.hits : [];
    pagesRead++;

    if (hits.length === 0) break;

    const lastHit = hits[hits.length - 1];
    searchAfterKey = lastHit.sort;

    if (hits.length < 50) break;
  }

  return { success: pagesRead > 0, pagesRead };
}
