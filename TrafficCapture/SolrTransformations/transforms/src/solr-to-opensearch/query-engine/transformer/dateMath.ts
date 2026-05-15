/**
 * Solr → OpenSearch date-math translation.
 *
 * Solr date-math is documented at
 *   https://solr.apache.org/guide/solr/latest/indexing-guide/date-formatting-math.html
 *
 * OpenSearch date-math is documented at
 *   https://opensearch.org/docs/latest/field-types/supported-field-types/date/#date-math
 *
 * Differences this helper bridges:
 *
 *   1. The "NOW" anchor:
 *        Solr        : `NOW`        (uppercase)
 *        OpenSearch  : `now`        (lowercase)
 *
 *   2. Unit suffix names. Solr accepts singular and plural English-language
 *      unit names; OpenSearch uses single-letter abbreviations:
 *
 *        Solr (any of)             | OpenSearch
 *        --------------------------+-----------
 *        YEAR / YEARS              | y
 *        MONTH / MONTHS            | M    (capital M — `m` would mean minute)
 *        DAY / DAYS / DATE         | d
 *        HOUR / HOURS              | h
 *        MINUTE / MINUTES          | m
 *        SECOND / SECONDS          | s
 *        MILLI / MILLIS / MILLISECOND / MILLISECONDS | (unsupported in OS;
 *                                                       the millisecond unit
 *                                                       has no rounding form
 *                                                       in OpenSearch DSL.
 *                                                       We pass it through
 *                                                       and let OS reject it
 *                                                       loudly rather than
 *                                                       silently dropping.)
 *
 *   3. Rounding form. Both engines use `/UNIT`, so this is a pure unit
 *      rename:  `NOW/MONTH` → `now/M`,  `NOW/MONTH-6MONTHS` → `now/M-6M`.
 *
 *   4. ISO-8601 absolute timestamps (`2020-01-01T00:00:00Z`) are accepted
 *      verbatim by OpenSearch and pass through unchanged.
 *
 * Anything that is *not* recognizable Solr date-math is returned unchanged so
 * pre-existing numeric / lexical range bounds keep working.
 */

/**
 * Map a Solr date-math unit name (case-insensitive, possibly plural) to its
 * OpenSearch single-letter equivalent. Returns `null` if the unit is not a
 * recognized Solr date-math unit.
 */
function solrUnitToOpenSearch(unit: string): string | null {
  switch (unit.toUpperCase()) {
    case 'YEAR':
    case 'YEARS':
      return 'y';
    case 'MONTH':
    case 'MONTHS':
      return 'M';
    case 'DAY':
    case 'DAYS':
    case 'DATE':
      return 'd';
    case 'HOUR':
    case 'HOURS':
      return 'h';
    case 'MINUTE':
    case 'MINUTES':
      return 'm';
    case 'SECOND':
    case 'SECONDS':
      return 's';
    default:
      return null;
  }
}

/**
 * Translate a single Solr date-math expression (rooted at `NOW` or a value
 * after the absolute-date `Z` anchor) to OpenSearch date-math.
 *
 * Walks the string left-to-right matching `(±N)?UNIT` pairs and rounding
 * `/UNIT` segments. Returns `null` if any segment is unrecognized — the
 * caller is then expected to leave the bound unchanged.
 *
 * Note: Solr also supports an absolute-date prefix form like
 *   `2020-01-01T00:00:00Z+6MONTHS`
 * OpenSearch supports the same form (`||+6M`) but with a different separator.
 * That mixed form is rare in practice; we translate only the bare `NOW`-rooted
 * form here. Pure ISO timestamps (no math suffix) are handled by the caller
 * via the early-return path before this function is invoked.
 */
function translateSolrDateMathBody(body: string): string | null {
  // Pattern: optional sign + digits + UNIT  OR  '/' + UNIT
  const segmentPattern = /([+-]\d+|\/)([A-Za-z]+)/g;
  let out = '';
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = segmentPattern.exec(body)) !== null) {
    if (match.index !== lastIndex) {
      // Unconsumed characters between segments — unrecognized form.
      return null;
    }
    const [whole, op, unit] = match;
    const osUnit = solrUnitToOpenSearch(unit);
    if (osUnit === null) {
      return null;
    }
    out += op + osUnit;
    lastIndex = match.index + whole.length;
  }
  if (lastIndex !== body.length) {
    // Trailing garbage — unrecognized.
    return null;
  }
  return out;
}

/**
 * Translate a Solr range-bound value to its OpenSearch equivalent.
 *
 * - `NOW` and `NOW`-rooted date-math are translated.
 * - ISO-8601 absolute timestamps pass through unchanged (OpenSearch accepts
 *   them natively).
 * - Numeric / string bounds pass through unchanged.
 * - Anything that *looks* like Solr date-math but contains an unrecognized
 *   unit is returned unchanged so the failure mode is "OS rejects it loudly"
 *   rather than "silent semantic drift".
 *
 * @param bound - The raw Solr bound value (e.g. `"NOW-365DAYS"`,
 *                `"2020-01-01T00:00:00Z"`, `"42"`).
 * @returns The bound rewritten for OpenSearch, or the original input if no
 *          translation is needed or no safe translation is possible.
 */
export function translateSolrDateMath(bound: string): string {
  if (!bound.startsWith('NOW')) {
    return bound;
  }
  // bound is exactly "NOW"
  if (bound.length === 3) {
    return 'now';
  }
  const tail = bound.slice(3);
  const translatedTail = translateSolrDateMathBody(tail);
  if (translatedTail === null) {
    // Couldn't safely translate — leave it alone. OpenSearch will reject
    // unknown units loudly rather than us silently producing wrong dates.
    return bound;
  }
  return 'now' + translatedTail;
}
