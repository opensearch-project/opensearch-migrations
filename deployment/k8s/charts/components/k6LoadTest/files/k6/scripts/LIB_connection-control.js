/**
 * Connection mode helpers.
 *
 * The Capture Proxy groups all requests on a single TCP connection into one
 * TrafficStream. The Replayer then replays each stream on its own connection,
 * preserving intra-stream ordering. Cross-stream ordering depends on Kafka
 * partition assignment and replayer scheduling.
 *
 * pinned()  — keep-alive (k6 default); all VU requests share one connection → one stream.
 *             Sequences arrive at the Replayer in order. Use to validate in-order replay.
 *
 * spread()  — Sends Connection: close to the server, asking it to tear down the TCP
 *             connection after each response. Each request thereby lands on its own stream.
 *             Sequences may be replayed out of order across Kafka partitions. Use to surface
 *             cross-partition ordering differences in output_tuples.log.
 *
 *             LIMITATION: k6 does not enforce connection teardown from this header
 *             client-side. The connection is only closed if the server (Capture Proxy /
 *             OpenSearch) echoes Connection: close back in the response. For a guaranteed
 *             client-side teardown independent of server behaviour, set NO_CONNECTION_REUSE=true
 *             in addition to CONNECTION_MODE=spread — this adds noConnectionReuse: true to the
 *             k6 options object, which disables keep-alive at the transport level for all VUs.
 */

const JSON_CONTENT = { 'Content-Type': 'application/json' };

export function pinned() {
  return { headers: { ...JSON_CONTENT } };
}

export function spread() {
  return { headers: { ...JSON_CONTENT, Connection: 'close' } };
}
