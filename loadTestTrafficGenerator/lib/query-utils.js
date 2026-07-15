/**
 * Shared query execution helpers used by all schema modules in lib/data/.
 *
 * The low-level functions (flatSearch, aggSearch, scrollSequence, searchAfterSequence)
 * handle HTTP calls and k6 checks.  The factory functions (makeFlatSearch, makeAggSearch,
 * makeSearchAfterSequence) build schema-bound versions of those functions from a field-name
 * config, so schema modules only need to declare config — no query logic to repeat.
 */

import http from 'k6/http';
import { check } from 'k6';

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export { pick };

export function pickRange(ranges) {
  return ranges[Math.floor(Math.random() * ranges.length)];
}

// ── Low-level executors ───────────────────────────────────────────────────────

function flatSearch(proxyUrl, index, buildBodyFn, fieldValues, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_search`,
    JSON.stringify(buildBodyFn(fieldValues)),
    { ...connParams, tags: { name: 'search_flat' } },
  );
  check(res, { 'flat search (200)': (r) => r.status === 200 });
  return res;
}

function aggSearch(proxyUrl, index, buildBodyFn, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_search`,
    JSON.stringify(buildBodyFn()),
    { ...connParams, tags: { name: 'search_agg' } },
  );
  check(res, { 'agg search (200)': (r) => r.status === 200 });
  return res;
}

// ── Factories ─────────────────────────────────────────────────────────────────

/**
 * Returns a flatSearch function bound to the given field config.
 *
 * config:
 *   termA:  { field, key }  — term filter used in variants 0 and 3
 *   rangeA: { field, key }  — range filter used in variant 1
 *   termB:  { field, key }  — term filter used in variant 2
 *   rangeB: { field, key }  — range filter used in variant 3
 *
 * `key` is the property name on the fieldValues object (from field-value-sample.json).
 * For range fields, fieldValues[key].ranges must be an array of [min, max] pairs.
 */
export function makeFlatSearch({ termA, rangeA, termB, rangeB }) {
  function buildBody(fieldValues) {
    const variant = Math.floor(Math.random() * 4);
    switch (variant) {
      case 0:
        return { size: 10, query: { term: { [termA.field]: pick(fieldValues[termA.key]) } } };
      case 1: {
        const r = pickRange(fieldValues[rangeA.key].ranges);
        return { size: 10, query: { range: { [rangeA.field]: { gte: r[0], lte: r[1] } } } };
      }
      case 2:
        return { size: 10, query: { term: { [termB.field]: pick(fieldValues[termB.key]) } } };
      case 3: {
        const r = pickRange(fieldValues[rangeB.key].ranges);
        return {
          size: 10,
          query: {
            bool: {
              filter: [
                { term: { [termA.field]: pick(fieldValues[termA.key]) } },
                { range: { [rangeB.field]: { gte: r[0], lte: r[1] } } },
              ],
            },
          },
        };
      }
      default:
        return { size: 10, query: { match_all: {} } };
    }
  }
  return (proxyUrl, index, fieldValues, connParams) =>
    flatSearch(proxyUrl, index, buildBody, fieldValues, connParams);
}

/**
 * Returns an aggSearch function bound to the given field config.
 *
 * config:
 *   dateHistogram: { field, interval, format }  — date_histogram aggregation
 *   termsA:        string  — field for a simple terms agg
 *   termsB:        string  — outer field for a nested terms + avg agg
 *   avgField:      string  — inner avg field for the nested agg
 *   termsDefault:  string  — field for the default-case terms agg
 */
export function makeAggSearch({ dateHistogram, termsA, termsB, avgField, termsDefault }) {
  function buildBody() {
    const variant = Math.floor(Math.random() * 3);
    switch (variant) {
      case 0:
        return {
          size: 0,
          aggs: {
            by_date: {
              date_histogram: {
                field:             dateHistogram.field,
                calendar_interval: dateHistogram.interval,
                format:            dateHistogram.format,
              },
            },
          },
        };
      case 1:
        return { size: 0, aggs: { by_category: { terms: { field: termsA, size: 10 } } } };
      case 2:
        return {
          size: 0,
          aggs: {
            by_group: {
              terms: { field: termsB, size: 10 },
              aggs:  { avg_metric: { avg: { field: avgField } } },
            },
          },
        };
      default:
        return { size: 0, aggs: { by_default: { terms: { field: termsDefault, size: 10 } } } };
    }
  }
  return (proxyUrl, index, connParams) =>
    aggSearch(proxyUrl, index, buildBody, connParams);
}

/**
 * Returns a searchAfterSequence function that sorts on the given field.
 * Each schema uses a different timestamp/datetime field as the primary sort key.
 */
export function makeSearchAfterSequence(sortField) {
  return (proxyUrl, index, connParams, maxPages) =>
    searchAfterSequence(proxyUrl, index, connParams, maxPages, sortField);
}

// ── Deep-paging ───────────────────────────────────────────────────────────────

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

function searchAfterSequence(proxyUrl, index, connParams, maxPages, sortField) {
  let searchAfterKey = null;
  let pagesRead = 0;

  for (let page = 0; page < maxPages; page++) {
    const body = {
      size: 50,
      sort: [{ [sortField]: 'asc' }, { _id: 'asc' }],
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
