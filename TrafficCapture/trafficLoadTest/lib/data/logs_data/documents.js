/**
 * Log event document generator.
 *
 * Produces synthetic structured log entries matching data/logs_data/mapping.json.
 * Field distribution is weighted toward realistic production traffic shapes
 * (INFO-heavy log levels, 2xx-heavy status codes).
 */

import { randomElement, randomInt, randomBulkBatch as _randomBulkBatch } from '../../doc-utils.js';

// Weighted toward INFO to reflect typical production log volumes
export const LEVELS       = ['DEBUG', 'INFO', 'INFO', 'INFO', 'WARN', 'ERROR', 'FATAL'];
export const SERVICES     = ['api-gateway', 'auth-service', 'user-service', 'payment-service', 'notification-service'];
export const HOSTS        = ['host-01', 'host-02', 'host-03', 'host-04', 'host-05'];
export const ENVIRONMENTS = ['prod', 'staging', 'dev'];
// Weighted toward 200 to reflect typical HTTP traffic
export const STATUS_CODES = [200, 200, 200, 201, 400, 401, 403, 404, 500, 503];

export const DURATION_MS_RANGE = { min: 1, max: 5000 };
const MESSAGES     = [
  'Request processed successfully',
  'Authentication successful',
  'Cache miss, fetching from database',
  'Database query completed',
  'User session expired',
  'Rate limit exceeded for client',
  'Invalid request parameters',
  'Connection pool exhausted',
  'Service degraded, using fallback',
  'Retrying failed downstream call',
];

function randomTimestamp() {
  const base     = new Date('2024-01-01T00:00:00Z').getTime();
  const offsetMs = Math.floor(Math.random() * 365 * 24 * 60 * 60 * 1000);
  return new Date(base + offsetMs).toISOString();
}

function randomHex(len) {
  let s = '';
  for (let i = 0; i < len; i++) {
    s += Math.floor(Math.random() * 16).toString(16);
  }
  return s;
}

function randomTraceId() {
  return `${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}`;
}

export function randomDocument() {
  return {
    '@timestamp':  randomTimestamp(),
    level:         randomElement(LEVELS),
    service:       randomElement(SERVICES),
    host:          randomElement(HOSTS),
    message:       randomElement(MESSAGES),
    trace_id:      randomTraceId(),
    span_id:       randomHex(16),
    duration_ms:   randomInt(1, 5000),
    status_code:   randomElement(STATUS_CODES),
    environment:   randomElement(ENVIRONMENTS),
  };
}

/** Partial-update body for a sequence update step. */
export function randomUpdateBody() {
  return { level: randomElement(LEVELS) };
}

export function randomBulkBatch(index, batchSize) {
  return _randomBulkBatch(index, batchSize, randomDocument);
}

/**
 * Used by setup() in search.js and mixed.js to guard against a stale index
 * created with dynamic mapping (wrong field types for aggregations).
 */
export const CRITICAL_MAPPING_CHECK = { field: 'level', type: 'keyword' };
