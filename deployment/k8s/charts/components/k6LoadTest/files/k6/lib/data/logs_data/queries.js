/**
 * Query config for the Logs schema.
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

import { LEVELS, SERVICES } from './documents.js';

export { scrollSequence };

const DURATION_RANGES = [[1, 100], [100, 1000], [1000, 5000]];

export const flatSearch = makeFlatSearch({
  termA:  { field: 'level',       values: LEVELS },
  rangeA: { field: 'duration_ms', ranges: DURATION_RANGES },
  termB:  { field: 'service',     values: SERVICES },
  rangeB: { field: 'duration_ms', ranges: DURATION_RANGES },
});

export const aggSearch = makeAggSearch({
  dateHistogram: { field: '@timestamp', interval: 'hour', format: 'yyyy-MM-dd HH:mm' },
  termsA:        'level',
  termsB:        'service',
  avgField:      'duration_ms',
  termsDefault:  'environment',
});

export const searchAfterSequence = makeSearchAfterSequence('@timestamp');
