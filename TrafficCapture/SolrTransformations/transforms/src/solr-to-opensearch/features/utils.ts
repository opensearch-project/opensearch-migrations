import { JavaMap } from '../context';

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
