/**
 * Connection mode helpers — Phase 2.
 *
 * The Capture Proxy groups all requests on a single TCP connection into one
 * TrafficStream. The Replayer then replays each stream on its own connection,
 * preserving intra-stream ordering. Cross-stream ordering depends on Kafka
 * partition assignment and replayer scheduling.
 *
 * pinned()  — keep-alive (k6 default); all VU requests share one connection → one stream.
 *             Sequences arrive at the Replayer in order. Use to validate in-order replay.
 *
 * spread()  — Connection: close forces a new TCP connection per request → each request
 *             lands on a separate stream. Sequences may be replayed out of order across
 *             Kafka partitions. Use to surface cross-partition ordering differences in
 *             output_tuples.log.
 */

const JSON_CONTENT = { 'Content-Type': 'application/json' };

export function pinned() {
  return { headers: { ...JSON_CONTENT } };
}

export function spread() {
  return { headers: { ...JSON_CONTENT, Connection: 'close' } };
}
