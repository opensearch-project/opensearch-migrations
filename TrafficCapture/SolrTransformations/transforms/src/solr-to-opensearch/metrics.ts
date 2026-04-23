/**
 * Limitation metrics — lightweight counters for known Solr→OpenSearch gaps.
 *
 * Each metric maps 1-to-1 to a limitation shortcode in LIMITATIONS.md.
 * Context objects expose {@link emitMetric} so transforms can record
 * occurrences without importing the accumulator directly.
 */

/** All recognised limitation metric names (match LIMITATIONS.md shortcodes). */
export type TransformMetricName =
  | 'terms_offset'
  | 'date_range_gap'
  | 'date_range_gap_compound'
  | 'range_boundary';

/** Per-request accumulator — metric name → count. */
export type MetricsAccumulator = Map<TransformMetricName, number>;

/** Create a fresh accumulator for a new request context. */
export function createMetrics(): MetricsAccumulator {
  return new Map();
}

/** Increment a limitation counter by 1. */
export function incrementMetric(acc: MetricsAccumulator, metric: TransformMetricName): void {
  acc.set(metric, (acc.get(metric) ?? 0) + 1);
}

/** Write accumulated metrics onto the message map as `_metrics`. */
export function flushMetrics(acc: MetricsAccumulator, msg: { set(key: string, value: any): void }): void {
  if (acc.size === 0) return;
  const metricsObj = new Map<string, any>();
  for (const [key, value] of acc.entries()) {
    metricsObj.set(key, value);
  }
  msg.set('_metrics', metricsObj);
}
