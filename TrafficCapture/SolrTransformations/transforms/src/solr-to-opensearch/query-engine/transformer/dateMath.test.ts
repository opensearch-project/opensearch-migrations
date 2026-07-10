/**
 * Unit tests for the Solr → OpenSearch date-math translation helper.
 *
 * Covers:
 *   - Bare `NOW`           → `now`
 *   - Solr unit names      → OpenSearch single-letter abbreviations
 *   - Compound expressions → preserve operator order (`NOW/MONTH-6MONTHS` → `now/M-6M`)
 *   - ISO timestamps       → unchanged (OpenSearch accepts them natively)
 *   - Plain numbers/strings → unchanged
 *   - Unrecognized "NOW"-like input → unchanged (loud failure preferred over silent semantic drift)
 */

import { describe, it, expect } from 'vitest';
import { translateSolrDateMath } from './dateMath';

describe('translateSolrDateMath', () => {
  describe('bare NOW', () => {
    it('translates NOW to now', () => {
      expect(translateSolrDateMath('NOW')).toBe('now');
    });
  });

  describe('NOW with offset', () => {
    it('NOW-365DAYS → now-365d', () => {
      expect(translateSolrDateMath('NOW-365DAYS')).toBe('now-365d');
    });
    it('NOW+1HOUR → now+1h', () => {
      expect(translateSolrDateMath('NOW+1HOUR')).toBe('now+1h');
    });
    it('NOW-1MINUTE → now-1m', () => {
      expect(translateSolrDateMath('NOW-1MINUTE')).toBe('now-1m');
    });
    it('NOW+30SECONDS → now+30s', () => {
      expect(translateSolrDateMath('NOW+30SECONDS')).toBe('now+30s');
    });
    it('NOW-2YEARS → now-2y', () => {
      expect(translateSolrDateMath('NOW-2YEARS')).toBe('now-2y');
    });
    it('NOW-1MONTH → now-1M (capital M, not minute)', () => {
      expect(translateSolrDateMath('NOW-1MONTH')).toBe('now-1M');
    });
    it('NOW-1MONTHS (plural) → now-1M', () => {
      expect(translateSolrDateMath('NOW-1MONTHS')).toBe('now-1M');
    });
  });

  describe('NOW with rounding', () => {
    it('NOW/DAY → now/d', () => {
      expect(translateSolrDateMath('NOW/DAY')).toBe('now/d');
    });
    it('NOW/HOUR → now/h', () => {
      expect(translateSolrDateMath('NOW/HOUR')).toBe('now/h');
    });
    it('NOW/MONTH → now/M', () => {
      expect(translateSolrDateMath('NOW/MONTH')).toBe('now/M');
    });
    it('NOW/YEAR → now/y', () => {
      expect(translateSolrDateMath('NOW/YEAR')).toBe('now/y');
    });
  });

  describe('compound NOW expressions', () => {
    it('NOW/MONTH-6MONTHS → now/M-6M', () => {
      expect(translateSolrDateMath('NOW/MONTH-6MONTHS')).toBe('now/M-6M');
    });
    it('NOW/DAY+1HOUR → now/d+1h', () => {
      expect(translateSolrDateMath('NOW/DAY+1HOUR')).toBe('now/d+1h');
    });
    it('NOW-1YEAR/MONTH → now-1y/M', () => {
      expect(translateSolrDateMath('NOW-1YEAR/MONTH')).toBe('now-1y/M');
    });
    it('NOW/MONTH+1DAY/HOUR (round, add, round) → now/M+1d/h', () => {
      expect(translateSolrDateMath('NOW/MONTH+1DAY/HOUR')).toBe('now/M+1d/h');
    });
  });

  describe('case sensitivity of unit names', () => {
    it('accepts mixed-case unit names', () => {
      expect(translateSolrDateMath('NOW-1Day')).toBe('now-1d');
    });
  });

  describe('passthrough — values that are not Solr date-math', () => {
    it('ISO-8601 timestamp passes through unchanged', () => {
      expect(translateSolrDateMath('2020-01-01T00:00:00Z')).toBe('2020-01-01T00:00:00Z');
    });
    it('ISO-8601 timestamp with millis passes through', () => {
      expect(translateSolrDateMath('2020-01-01T00:00:00.123Z')).toBe('2020-01-01T00:00:00.123Z');
    });
    it('integer string passes through', () => {
      expect(translateSolrDateMath('42')).toBe('42');
    });
    it('decimal string passes through', () => {
      expect(translateSolrDateMath('1.5')).toBe('1.5');
    });
    it('alpha string passes through', () => {
      expect(translateSolrDateMath('abc')).toBe('abc');
    });
    it('empty string passes through', () => {
      expect(translateSolrDateMath('')).toBe('');
    });
  });

  describe('unrecognized NOW-like input — preserve loud failure', () => {
    it('NOW with unknown unit returns input unchanged so OS rejects loudly', () => {
      expect(translateSolrDateMath('NOW-1FORTNIGHT')).toBe('NOW-1FORTNIGHT');
    });
    it('NOW with malformed offset returns input unchanged', () => {
      // Two operators in a row is not a valid Solr date-math expression.
      expect(translateSolrDateMath('NOW--1DAY')).toBe('NOW--1DAY');
    });
    it('NOW with trailing garbage returns input unchanged', () => {
      expect(translateSolrDateMath('NOW-1DAYxyz')).toBe('NOW-1DAYxyz');
    });
    it('value that starts with NOW but is actually a different identifier passes through', () => {
      // `NOWHERE` happens to start with NOW but is not date-math. The helper
      // notices this because nothing matches the (op)(unit) segment pattern
      // and returns the input unchanged.
      expect(translateSolrDateMath('NOWHERE')).toBe('NOWHERE');
    });
  });
});
