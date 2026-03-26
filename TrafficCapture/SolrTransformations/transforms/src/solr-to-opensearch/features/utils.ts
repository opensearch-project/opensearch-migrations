import { JavaMap } from '../context';

// region Solr date math gap detection and conversion

/**
 * Solr date-math unit → OpenSearch interval suffix.
 *
 * Note: OpenSearch uses lowercase for all units except 'M' (month) to
 * distinguish it from 'm' (minute).
 */
const SOLR_UNIT_TO_OS: Record<string, string> = {
  YEAR: 'y',
  YEARS: 'y',
  MONTH: 'M',
  MONTHS: 'M',
  DAY: 'd',
  DAYS: 'd',
  HOUR: 'h',
  HOURS: 'h',
  MINUTE: 'm',
  MINUTES: 'm',
  SECOND: 's',
  SECONDS: 's',
};

/**
 * Units whose length varies with the calendar and therefore **cannot** be
 * expressed as a fixed duration when the count is greater than 1.
 *
 * When the count is 1 we use `calendar_interval`; when > 1 we approximate
 * with a `fixed_interval` and emit a warning.
 */
const CALENDAR_ONLY_UNITS = new Set(['YEAR', 'YEARS', 'MONTH', 'MONTHS', 'DAY', 'DAYS']);

/** Approximate number of seconds in one calendar unit (used for fixed-interval fallback). */
const APPROX_SECONDS: Record<string, number> = {
  YEAR: 31536000,   // 365 × 24 × 3600
  YEARS: 31536000,
  MONTH: 2592000,   // 30 × 24 × 3600
  MONTHS: 2592000,
  DAY: 86400,       // 24 × 3600
  DAYS: 86400,
};

/** Matches a single date-math component like "+1MONTH" or "+2DAYS".
 *  Plural forms listed first so the regex engine doesn't short-circuit on the singular. */
const SOLR_DATE_COMPONENT_RE = /\+(\d+)(YEARS|YEAR|MONTHS|MONTH|DAYS|DAY|HOURS|HOUR|MINUTES|MINUTE|SECONDS|SECOND)/gi;

/** Matches the entire gap string — one or more components with nothing else. */
const SOLR_DATE_GAP_SINGLE_RE = /^\+(\d+)(YEAR|YEARS|MONTH|MONTHS|DAY|DAYS|HOUR|HOURS|MINUTE|MINUTES|SECOND|SECONDS)$/i;

/**
 * Return `true` if `gap` looks like a Solr date-math gap string.
 *
 * Supports both simple gaps ("+1MONTH") and compound gaps ("+1MONTH+2DAYS").
 */
export function isSolrDateMathGap(gap: string): boolean {
  // Build the full string from all matched components; if it equals the
  // original then the entire string is valid date math.
  const matches = gap.match(SOLR_DATE_COMPONENT_RE);
  if (!matches) return false;
  return matches.join('') === gap;
}

/** Result of converting a Solr date gap to an OpenSearch interval parameter. */
export interface DateGapInterval {
  /** The OpenSearch date-histogram parameter name to use. */
  type: 'calendar_interval' | 'fixed_interval';
  /** The interval value (e.g. "1M", "5m", "720h"). */
  value: string;
}

/** Exact number of seconds in one fixed-duration Solr date math unit. */
const EXACT_SECONDS: Record<string, number> = {
  HOUR: 3600,
  HOURS: 3600,
  MINUTE: 60,
  MINUTES: 60,
  SECOND: 1,
  SECONDS: 1,
};

/**
 * Convert a total number of seconds to the most natural OpenSearch
 * fixed_interval string: prefer hours if evenly divisible, then minutes,
 * then seconds.
 */
function secondsToFixedInterval(totalSeconds: number): string {
  if (totalSeconds % 3600 === 0) {
    return `${totalSeconds / 3600}h`;
  }
  if (totalSeconds % 60 === 0) {
    return `${totalSeconds / 60}m`;
  }
  return `${totalSeconds}s`;
}

/**
 * Convert a Solr date-math gap string to an OpenSearch date-histogram
 * interval specification.
 *
 * Supports simple gaps ("+1MONTH") and compound gaps ("+1MONTH+2DAYS").
 *
 * Rules for **simple** (single-component) gaps:
 *   • count = 1 for any unit → `calendar_interval` (e.g. "1M", "1d")
 *   • count > 1 for fixed-duration units (h/m/s) → `fixed_interval`
 *   • count > 1 for calendar units (y/M/d) → `fixed_interval` with an
 *     approximation (30 d/month, 365 d/year, 24 h/day) and a console warning
 *
 * **Compound** gaps are always converted to `fixed_interval` by summing
 * all components into total seconds, with calendar units approximated.
 *
 * @throws Error if the gap string does not match the expected pattern.
 */
export function convertSolrDateGap(gap: string): DateGapInterval {
  // Try simple (single-component) path first for the common case
  const singleMatch = SOLR_DATE_GAP_SINGLE_RE.exec(gap);
  if (singleMatch) {
    return convertSimpleDateGap(gap, Number(singleMatch[1]), singleMatch[2].toUpperCase());
  }

  // Try compound path
  return convertCompoundDateGap(gap);
}

/** Handle a single-component gap like "+1MONTH" or "+5MINUTES". */
function convertSimpleDateGap(gap: string, count: number, solrUnit: string): DateGapInterval {
  const osUnit = SOLR_UNIT_TO_OS[solrUnit];
  if (!osUnit) {
    throw new Error(`Unknown Solr date unit: "${solrUnit}"`);
  }

  // Single-unit → always calendar_interval
  if (count === 1) {
    return { type: 'calendar_interval', value: `1${osUnit}` };
  }

  // Multi-count, fixed-duration (h/m/s) → fixed_interval directly
  if (!CALENDAR_ONLY_UNITS.has(solrUnit)) {
    return { type: 'fixed_interval', value: `${count}${osUnit}` };
  }

  // Multi-count, calendar unit (y/M/d) → approximate as fixed duration
  const totalSeconds = count * APPROX_SECONDS[solrUnit];
  const value = secondsToFixedInterval(totalSeconds);
  console.warn(
    `[solr-date-gap] Solr gap "${gap}" approximated as fixed_interval "${value}" — bucket boundaries may not align with calendar ${solrUnit.toLowerCase()} boundaries.`,
  );
  return { type: 'fixed_interval', value };
}

/**
 * Handle a compound gap like "+1MONTH+2DAYS" or "+1YEAR+6MONTHS".
 *
 * All components are summed into total seconds. Calendar units are
 * approximated. A warning is always emitted because compound gaps
 * cannot be represented as a single calendar_interval.
 */
function convertCompoundDateGap(gap: string): DateGapInterval {
  // Use a fresh regex instance to avoid lastIndex issues with the global flag
  // Plural forms listed first so the regex engine doesn't short-circuit on the singular
  const re = /\+(\d+)(YEARS|YEAR|MONTHS|MONTH|DAYS|DAY|HOURS|HOUR|MINUTES|MINUTE|SECONDS|SECOND)/gi;
  const components: Array<{ count: number; unit: string }> = [];
  let reconstructed = '';
  let m: RegExpExecArray | null;

  while ((m = re.exec(gap)) !== null) {
    components.push({ count: Number(m[1]), unit: m[2].toUpperCase() });
    reconstructed += m[0];
  }

  if (components.length === 0 || reconstructed !== gap) {
    throw new Error(`Unrecognised Solr date gap: "${gap}"`);
  }

  // Sum all components into total seconds
  let totalSeconds = 0;
  for (const { count, unit } of components) {
    const approx = APPROX_SECONDS[unit];
    if (approx == null) {
      const exact = EXACT_SECONDS[unit];
      if (exact == null) {
        throw new Error(`Unknown Solr date unit in compound gap: "${unit}"`);
      } else {
        totalSeconds += count * exact;
      }
    } else {
      totalSeconds += count * approx;
    }
  }

  const value = secondsToFixedInterval(totalSeconds);

  console.warn(
    `[solr-date-gap] Compound Solr gap "${gap}" approximated as fixed_interval "${value}" — bucket boundaries may not align with calendar boundaries.`,
  );
  return { type: 'fixed_interval', value };
}

// endregion

const SORT_KEY_MAP: Record<string, string> = {
  count: '_count',
  index: '_key',
};

/**
 * Convert a Solr sort specification to an OpenSearch order map.
 *
 * Accepts either:
 *   - A string like "count desc" or "index asc"
 *   - A Map like {count: "desc"}
 *
 * Translates Solr sort keys (count, index) to their OpenSearch equivalents (_count, _key).
 */
export function convertSort(sortSpec: string | JavaMap): JavaMap {
  const order = new Map<string, any>();

  if (typeof sortSpec === 'string') {
    const parts = sortSpec.trim().split(/\s+/);
    const key = parts[0];
    const direction = parts[1] || 'desc';
    const osKey = SORT_KEY_MAP[key] || key;
    order.set(osKey, direction.toLowerCase());
  } else if (isMapLike(sortSpec)) {
    for (const key of sortSpec.keys()) {
      const direction = (sortSpec.get(key) || 'desc').toString().toLowerCase();
      const osKey = SORT_KEY_MAP[key] || key;
      order.set(osKey, direction);
    }
  }

  return order;
}

/** Check if a value looks like a JavaMap / Map (has .get and .keys methods). */
export function isMapLike(v: any): v is JavaMap {
  return (
    v != null &&
    typeof v === 'object' &&
    typeof v.get === 'function' &&
    typeof v.keys === 'function'
  );
}
