/**
 * Query config for the Logs schema.
 * Field names and fieldValues keys are the only schema-specific details here;
 * all query logic lives in lib/query-utils.js.
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * fieldValues is the parsed data/logs_data/field-value-sample.json object.
 */

import {
  makeFlatSearch,
  makeAggSearch,
  makeSearchAfterSequence,
  scrollSequence,
} from '../../query-utils.js';

export { scrollSequence };

export const flatSearch = makeFlatSearch({
  termA:  { field: 'level',       key: 'levels' },
  rangeA: { field: 'duration_ms', key: 'duration_ms' },
  termB:  { field: 'service',     key: 'services' },
  rangeB: { field: 'duration_ms', key: 'duration_ms' },
});

export const aggSearch = makeAggSearch({
  dateHistogram: { field: '@timestamp', interval: 'hour', format: 'yyyy-MM-dd HH:mm' },
  termsA:        'level',
  termsB:        'service',
  avgField:      'duration_ms',
  termsDefault:  'environment',
});

export const searchAfterSequence = makeSearchAfterSequence('@timestamp');
