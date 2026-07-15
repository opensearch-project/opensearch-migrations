/**
 * NYC Taxis document generator.
 *
 * Mirrors the field shapes produced by DataGenerator/NycTaxis.java so that
 * k6-generated docs are accepted by the same index mapping (dynamic: strict).
 * Date format matches fieldDateISO(): "yyyy-MM-dd HH:mm:ss".
 */

import { randomElement, randomInt, randomFloat, randomBulkBatch as _randomBulkBatch } from '../../doc-utils.js';

const TRIP_TYPES      = ['1', '2'];
const PAYMENT_TYPES   = ['1', '2', '3', '4'];
const STORE_FWD_FLAGS = ['Y', 'N'];
const VENDOR_IDS      = ['1', '2'];

function randomNycLocation() {
  return [
    randomFloat(-74.05, -73.75), // longitude
    randomFloat(40.63,  40.85),  // latitude
  ];
}

// Returns a datetime string in the format expected by fieldDateISO(): "yyyy-MM-dd HH:mm:ss"
function randomDatetime() {
  const base     = new Date('2024-01-01T00:00:00Z').getTime();
  const offsetMs = Math.floor(Math.random() * 365 * 24 * 60 * 60 * 1000);
  const d        = new Date(base + offsetMs);
  const pad      = (n) => String(n).padStart(2, '0');
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  );
}

/**
 * Generate one random NYC Taxis document.
 * Only fields present in createDocs() are included; optional mapped fields
 * (cab_color, ehail_fee, surcharge, vendor_name) are omitted because
 * dynamic: strict rejects unknown fields but allows missing mapped ones.
 */
export function randomDocument() {
  return {
    total_amount:          randomFloat(5.0,  50.0),
    improvement_surcharge: 0.3,
    pickup_location:       randomNycLocation(),
    pickup_datetime:       randomDatetime(),
    trip_type:             randomElement(TRIP_TYPES),
    dropoff_datetime:      randomDatetime(),
    rate_code_id:          '1',
    tolls_amount:          randomFloat(0.0,  5.0),
    dropoff_location:      randomNycLocation(),
    passenger_count:       randomInt(1, 4),
    fare_amount:           randomFloat(5.0,  50.0),
    extra:                 randomFloat(0.0,  1.0),
    trip_distance:         randomFloat(0.5, 20.0),
    tip_amount:            randomFloat(0.0, 15.0),
    store_and_fwd_flag:    randomElement(STORE_FWD_FLAGS),
    payment_type:          randomElement(PAYMENT_TYPES),
    mta_tax:               0.5,
    vendor_id:             randomElement(VENDOR_IDS),
  };
}

/** Partial-update body for a sequence update step. */
export function randomUpdateBody() {
  return { total_amount: parseFloat((Math.random() * 45 + 5).toFixed(2)) };
}

export function randomBulkBatch(index, batchSize) {
  return _randomBulkBatch(index, batchSize, randomDocument);
}

/**
 * Used by setup() in search.js and mixed.js to guard against a stale index
 * created with dynamic mapping (wrong field types for aggregations).
 */
export const CRITICAL_MAPPING_CHECK = { field: 'vendor_id', type: 'keyword' };
