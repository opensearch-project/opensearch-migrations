/**
 * Query helpers for the Logs scenario.
 *
 * Defines the schema-specific query bodies and delegates HTTP execution
 * and deep-paging to lib/query-utils.js.
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * fieldValues is the parsed data/logs_data/field-value-sample.json object.
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
      return { size: 10, query: { term: { level: pick(fieldValues.levels) } } };
    case 1: {
      const r = pickRange(fieldValues.duration_ms.ranges);
      return { size: 10, query: { range: { duration_ms: { gte: r[0], lte: r[1] } } } };
    }
    case 2:
      return { size: 10, query: { term: { service: pick(fieldValues.services) } } };
    case 3: {
      const r = pickRange(fieldValues.duration_ms.ranges);
      return {
        size: 10,
        query: {
          bool: {
            filter: [
              { term: { level: pick(fieldValues.levels) } },
              { range: { duration_ms: { gte: r[0], lte: r[1] } } },
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
          events_by_hour: {
            date_histogram: {
              field: '@timestamp',
              calendar_interval: 'hour',
              format: 'yyyy-MM-dd HH:mm',
            },
          },
        },
      };
    case 1:
      return {
        size: 0,
        aggs: { by_level: { terms: { field: 'level', size: 10 } } },
      };
    case 2:
      return {
        size: 0,
        aggs: {
          by_service: {
            terms: { field: 'service', size: 10 },
            aggs: { avg_duration: { avg: { field: 'duration_ms' } } },
          },
        },
      };
    default:
      return { size: 0, aggs: { by_environment: { terms: { field: 'environment', size: 10 } } } };
  }
}

export function aggSearch(proxyUrl, index, connParams) {
  return _aggSearch(proxyUrl, index, buildAggBody, connParams);
}

// ── search_after sequence ────────────────────────────────────────────────────

export function searchAfterSequence(proxyUrl, index, connParams, maxPages) {
  return _searchAfterSequence(proxyUrl, index, connParams, maxPages, '@timestamp');
}
