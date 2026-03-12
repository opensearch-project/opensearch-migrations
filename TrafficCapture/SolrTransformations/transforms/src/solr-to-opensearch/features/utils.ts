import { JavaMap } from '../context';

const SORT_KEY_MAP: Record<string, string> = {
  count: '_count',
  index: '_key',
};

/**
 * Parse a Solr sort spec like "count desc" or "index asc" into an OpenSearch order map.
 */
export function convertSort(sortSpec: string): JavaMap {
  const order = new Map();
  const parts = sortSpec.trim().split(/\s+/);
  const key = parts[0];
  const direction = parts[1] || 'desc';
  const osKey = SORT_KEY_MAP[key] || key;
  order.set(osKey, direction.toLowerCase());
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
