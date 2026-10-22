/**
 * Query config for the NYC Taxis schema.
 * Field names and value sets come from documents.js (single source of truth).
 * Range buckets define search coverage strategy and live here, not in documents.js.
 * All query logic lives in lib/query-utils.js.
 */

import {
  makeFlatSearch,
  makeAggSearch,
  makeSearchAfterSequence,
  scrollSequence,
} from '../../query-utils.js';

import { VENDOR_IDS, PAYMENT_TYPES, FARE_AMOUNT_RANGE, TRIP_DISTANCE_RANGE } from './documents.js';

export { scrollSequence };

const FARE_RANGES     = [[5, 15], [15, 30], [30, 50]];
const DISTANCE_RANGES = [[0.5, 5], [5, 10], [10, 20]];

export const flatSearch = makeFlatSearch({
  termA:  { field: 'vendor_id',     values: VENDOR_IDS },
  rangeA: { field: 'fare_amount',   ranges: FARE_RANGES },
  termB:  { field: 'payment_type',  values: PAYMENT_TYPES },
  rangeB: { field: 'trip_distance', ranges: DISTANCE_RANGES },
});

export const aggSearch = makeAggSearch({
  dateHistogram: { field: 'pickup_datetime', interval: 'month', format: 'yyyy-MM' },
  termsA:        'payment_type',
  termsB:        'vendor_id',
  avgField:      'fare_amount',
  termsDefault:  'trip_type',
});

export const searchAfterSequence = makeSearchAfterSequence('pickup_datetime');
