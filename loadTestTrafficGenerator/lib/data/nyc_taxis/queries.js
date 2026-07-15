/**
 * Query helpers for the NYC Taxis scenario.
 *
 * Defines the schema-specific query bodies and delegates HTTP execution
 * and deep-paging to lib/query-utils.js.
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * fieldValues is the parsed data/nyc_taxis/field-value-sample.json object.
 */

import {
  pick, pickRange,
  flatSearch as _flatSearch,
  aggSearch as _aggSearch,
  scrollSequence,
  searchAfterSequence as _searchAfterSequence,
} from '../../query-utils.js';

export { scrollSequence };

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
  return _flatSearch(proxyUrl, index, buildFlatBody, fieldValues, connParams);
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
  return _aggSearch(proxyUrl, index, buildAggBody, connParams);
}

// ── search_after sequence ────────────────────────────────────────────────────

export function searchAfterSequence(proxyUrl, index, connParams, maxPages) {
  return _searchAfterSequence(proxyUrl, index, connParams, maxPages, 'pickup_datetime');
}
