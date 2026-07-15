/**
 * Shared query execution helpers used by all schema modules in lib/data/.
 *
 * Each schema module provides its own buildFlatBody / buildAggBody / sortField
 * and delegates to these functions, keeping the HTTP + check boilerplate in one place.
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

export function flatSearch(proxyUrl, index, buildBodyFn, fieldValues, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_search`,
    JSON.stringify(buildBodyFn(fieldValues)),
    { ...connParams, tags: { name: 'search_flat' } },
  );
  check(res, { 'flat search (200)': (r) => r.status === 200 });
  return res;
}

export function aggSearch(proxyUrl, index, buildBodyFn, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_search`,
    JSON.stringify(buildBodyFn()),
    { ...connParams, tags: { name: 'search_agg' } },
  );
  check(res, { 'agg search (200)': (r) => r.status === 200 });
  return res;
}

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

/**
 * Pages through results using search_after on sortField.
 * Each request is independent — no server-side state to clean up.
 * Returns { success: boolean, pagesRead: number }.
 */
export function searchAfterSequence(proxyUrl, index, connParams, maxPages, sortField) {
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
