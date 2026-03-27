import { describe, it, expect, vi } from 'vitest';
import { convertSort, isSolrDateMathGap, convertSolrDateGap } from './utils';
import type { JavaMap } from '../context';

// ---------------------------------------------------------------------------
// isSolrDateMathGap
// ---------------------------------------------------------------------------

describe('isSolrDateMathGap', () => {
  it.each([
    '+1MONTH',
    '+1MONTHS',
    '+1YEAR',
    '+1YEARS',
    '+1DAY',
    '+1DAYS',
    '+1HOUR',
    '+1HOURS',
    '+1MINUTE',
    '+1MINUTES',
    '+1SECOND',
    '+1SECONDS',
    '+5MINUTES',
    '+12HOURS',
    '+30DAYS',
  ])('should return true for "%s"', (gap) => {
    expect(isSolrDateMathGap(gap)).toBe(true);
  });

  it('should be case-insensitive', () => {
    expect(isSolrDateMathGap('+1month')).toBe(true);
    expect(isSolrDateMathGap('+1Month')).toBe(true);
  });

  it.each([
    '+1MONTH+2DAYS',
    '+1YEAR+6MONTHS',
    '+1DAY+12HOURS',
    '+1HOUR+30MINUTES',
    '+1YEAR+1MONTH+1DAY',
  ])('should return true for compound gap "%s"', (gap) => {
    expect(isSolrDateMathGap(gap)).toBe(true);
  });

  it.each([
    '10',
    '1MONTH',       // missing +
    '+MONTH',       // missing count
    '+0.5MONTH',    // decimal count
    'abc',
    '',
    '+1WEEK',       // unsupported unit
    '+1',           // missing unit
    '+1MONTH2DAYS', // missing + between components
  ])('should return false for "%s"', (gap) => {
    expect(isSolrDateMathGap(gap)).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// convertSolrDateGap — calendar_interval (count = 1)
// ---------------------------------------------------------------------------

describe('convertSolrDateGap', () => {
  describe('single-unit → calendar_interval', () => {
    it.each([
      ['+1YEAR', '1y'],
      ['+1MONTH', '1M'],
      ['+1DAY', '1d'],
      ['+1HOUR', '1h'],
      ['+1MINUTE', '1m'],
      ['+1SECOND', '1s'],
    ])('should convert "%s" to calendar_interval "%s"', (gap, expected) => {
      const result = convertSolrDateGap(gap);
      expect(result.type).toBe('calendar_interval');
      expect(result.value).toBe(expected);
    });

    it('should accept plural forms for count=1', () => {
      expect(convertSolrDateGap('+1MONTHS')).toEqual({ type: 'calendar_interval', value: '1M' });
      expect(convertSolrDateGap('+1YEARS')).toEqual({ type: 'calendar_interval', value: '1y' });
      expect(convertSolrDateGap('+1DAYS')).toEqual({ type: 'calendar_interval', value: '1d' });
    });
  });

  // ---------------------------------------------------------------------------
  // convertSolrDateGap — fixed_interval (count > 1, fixed-duration units)
  // ---------------------------------------------------------------------------

  describe('multi-unit fixed-duration → fixed_interval', () => {
    it.each([
      ['+5MINUTES', '5m'],
      ['+3HOURS', '3h'],
      ['+90SECONDS', '90s'],
      ['+12HOURS', '12h'],
      ['+30MINUTES', '30m'],
    ])('should convert "%s" to fixed_interval "%s"', (gap, expected) => {
      const result = convertSolrDateGap(gap);
      expect(result.type).toBe('fixed_interval');
      expect(result.value).toBe(expected);
    });
  });

  // ---------------------------------------------------------------------------
  // convertSolrDateGap — fixed_interval approximation (count > 1, calendar units)
  // ---------------------------------------------------------------------------

  describe('multi-unit calendar → fixed_interval approximation', () => {
    it('should approximate +2MONTHS as 1440h and warn', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+2MONTHS');
      expect(result.type).toBe('fixed_interval');
      // 2 × 720h = 1440h
      expect(result.value).toBe('1440h');
      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('approximated'));
      warnSpy.mockRestore();
    });

    it('should approximate +3YEARS as 26280h and warn', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+3YEARS');
      expect(result.type).toBe('fixed_interval');
      // 3 × 8760h = 26280h
      expect(result.value).toBe('26280h');
      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('approximated'));
      warnSpy.mockRestore();
    });

    it('should approximate +2DAYS as 48h and warn', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+2DAYS');
      expect(result.type).toBe('fixed_interval');
      // 2 × 24h = 48h
      expect(result.value).toBe('48h');
      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('approximated'));
      warnSpy.mockRestore();
    });

    it('should approximate +6MONTHS as 4320h', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+6MONTHS');
      expect(result.type).toBe('fixed_interval');
      // 6 × 720h = 4320h
      expect(result.value).toBe('4320h');
      warnSpy.mockRestore();
    });
  });

  // ---------------------------------------------------------------------------
  // convertSolrDateGap — compound gaps (e.g. +1MONTH+2DAYS)
  // ---------------------------------------------------------------------------

  describe('compound gaps → fixed_interval approximation', () => {
    it('should convert +1MONTH+2DAYS to fixed_interval and warn', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+1MONTH+2DAYS');
      expect(result.type).toBe('fixed_interval');
      // 1 month (720h) + 2 days (48h) = 768h
      expect(result.value).toBe('768h');
      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('Compound'));
      warnSpy.mockRestore();
    });

    it('should convert +1YEAR+6MONTHS to fixed_interval and warn', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+1YEAR+6MONTHS');
      expect(result.type).toBe('fixed_interval');
      // 1 year (8760h) + 6 months (4320h) = 13080h
      expect(result.value).toBe('13080h');
      warnSpy.mockRestore();
    });

    it('should convert +1DAY+12HOURS to fixed_interval and warn', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+1DAY+12HOURS');
      expect(result.type).toBe('fixed_interval');
      // 1 day (24h) + 12 hours = 36h
      expect(result.value).toBe('36h');
      warnSpy.mockRestore();
    });

    it('should convert +1HOUR+30MINUTES to fixed_interval using minutes', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+1HOUR+30MINUTES');
      expect(result.type).toBe('fixed_interval');
      // 1 hour (60m) + 30 minutes = 90m
      expect(result.value).toBe('90m');
      warnSpy.mockRestore();
    });

    it('should convert +1MINUTE+30SECONDS to fixed_interval using seconds', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+1MINUTE+30SECONDS');
      expect(result.type).toBe('fixed_interval');
      // 1 minute (60s) + 30 seconds = 90s
      expect(result.value).toBe('90s');
      warnSpy.mockRestore();
    });

    it('should handle three-component compound gap', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const result = convertSolrDateGap('+1YEAR+1MONTH+1DAY');
      expect(result.type).toBe('fixed_interval');
      // 1 year (8760h) + 1 month (720h) + 1 day (24h) = 9504h
      expect(result.value).toBe('9504h');
      warnSpy.mockRestore();
    });
  });

  // ---------------------------------------------------------------------------
  // convertSolrDateGap — error cases
  // ---------------------------------------------------------------------------

  describe('error handling', () => {
    it('should throw for an unrecognised gap string', () => {
      expect(() => convertSolrDateGap('10')).toThrow('Unrecognised Solr date gap');
    });

    it('should throw for a gap with an unknown unit', () => {
      expect(() => convertSolrDateGap('+1WEEK')).toThrow('Unrecognised Solr date gap');
    });

    it('should throw for an empty string', () => {
      expect(() => convertSolrDateGap('')).toThrow('Unrecognised Solr date gap');
    });
  });
});

// ---------------------------------------------------------------------------
// convertSort (moved from json-facets.test.ts for co-location with utils.ts)
// ---------------------------------------------------------------------------

describe('convertSort with Map input', () => {
  it('should convert a Map with count key to _count', () => {
    const sortMap = new Map([['count', 'desc']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('_count')).toBe('desc');
  });

  it('should convert a Map with index key to _key', () => {
    const sortMap = new Map([['index', 'asc']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('_key')).toBe('asc');
  });

  it('should default to desc when Map value is falsy', () => {
    const sortMap = new Map([['count', '']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('_count')).toBe('desc');
  });

  it('should pass through unknown sort keys from a Map', () => {
    const sortMap = new Map([['my_stat', 'ASC']]) as unknown as JavaMap;
    const order = convertSort(sortMap);
    expect(order.get('my_stat')).toBe('asc');
  });
});
