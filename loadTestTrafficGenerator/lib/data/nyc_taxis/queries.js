/**
 * Query config for the NYC Taxis schema.
 * Field names and fieldValues keys are the only schema-specific details here;
 * all query logic lives in lib/query-utils.js.
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * fieldValues is the parsed data/nyc_taxis/field-value-sample.json object.
 */

import {
  makeFlatSearch,
  makeAggSearch,
  makeSearchAfterSequence,
  scrollSequence,
} from '../../query-utils.js';

export { scrollSequence };

export const flatSearch = makeFlatSearch({
  termA:  { field: 'vendor_id',     key: 'vendor_ids' },
  rangeA: { field: 'fare_amount',   key: 'fare_amount' },
  termB:  { field: 'payment_type',  key: 'payment_types' },
  rangeB: { field: 'trip_distance', key: 'trip_distance' },
});

export const aggSearch = makeAggSearch({
  dateHistogram: { field: 'pickup_datetime', interval: 'month', format: 'yyyy-MM' },
  termsA:        'payment_type',
  termsB:        'vendor_id',
  avgField:      'fare_amount',
  termsDefault:  'trip_type',
});

export const searchAfterSequence = makeSearchAfterSequence('pickup_datetime');
