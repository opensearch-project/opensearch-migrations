# Capture Proxy

## Failure Modes

Definitions:
   * Mutating - HTTP Requests with method POST, PUT, DELETE, or PATCH with differentiating behavior defined in [ProxyChannelInitializer::shouldGuaranteeMessageOffloading](https://github.com/opensearch-project/opensearch-migrations/blob/15c62718032e02a089e158cdf61c753542c20175/TrafficCapture/trafficCaptureProxyServer/src/main/java/org/opensearch/migrations/trafficcapture/proxyserver/netty/ProxyChannelInitializer.java#L37)

| Component       | Type of Request | Scenario          | First Behavior                                                                                                                                                                                                                                                                                                                                                                                        | First Duration | Eventual Behavior                                                                                                                                                                                  |
|-----------------|-----------------|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Kafka**       | Mutating        | Kafka Error       | Data will be buffered and retried by the Kafka client based on the configured timeouts and the Kafka server's response. The delivery.timeout.ms setting of 10 seconds is the upper limit for how long the client will attempt to send data, including retries, before considering the operation failed. This is in the critical line for request proxying so the client will not receive a response.  | 10 Seconds     | When the completable future for offloading fails, a log statement will be outputted and the request will be forwarded to the destination service. No retry of this message's offloading will occur |
| **Kafka**       | Mutating        | Kafka Unavailable | Same behavior as <Kafka Error>                                                                                                                                                                                                                                                                                                                                                                        | 10 Seconds     | Same behavior as <Kafka Error>                                                                                                                                                                     |
| **Kafka**       | Non-Mutating    | Kafka Error       | Data will be buffered and retried by the Kafka client based on the configured timeouts and the Kafka server's response. The delivery.timeout.ms setting of 10 seconds is the upper limit for how long the client will attempt to send data, including retries, before considering the operation failed. This is not in the critical line for request proxying so request will be immediately proxied. | N/A            | N/A                                                                                                                                                                                                |
| **Kafka**       | Non-Mutating    | Kafka Unavailable | Same behavior as <Kafka Error>                                                                                                                                                                                                                                                                                                                                                                        | N/A            | N/A                                                                                                                                                                                                |
| **Destination** | Any             | Offline           | Proxy will directly pass through to the client the same behavior/response as seen by the destination. The proxy maintains 1:1 connection ratio between upstream clients and the destination.                                                                                                                                                                                                          | N/A            | N/A                                                                                                                                                                                                |
| **Logging**     | Any             | Any               | Capture Proxy logs to Standard Output/Error streams. This can be configured via Log4j2 properties which default to non-blocking behavior as specified in [PR#602](https://github.com/opensearch-project/opensearch-migrations/pull/602). For specific AWS Behavior, see configuration for the [AWS Log Driver](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/using_awslogs.html)        | N/A            | N/A                                                                                                                                                                                                |
| **Otel**        | Any             | Any               | By default there is no retry of the OTLP Exporter. This can be enabled by setting the appropriate JVM parameter or environment variable as specified in the [Java OTLP Documentation](https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/autoconfigure#otlp-exporter-span-metric-and-log-exporters)                                                                        | N/A            | N/A                                                                                                                                                                                                |

## HTTP/2 support 
The capture proxy supports HTTP/2 traffic via ALPN negotiation when the
`--enableHttp2` flag is set (default: off). Both client-facing and upstream-facing
SSL contexts advertise `h2,http/1.1`; when both peers select `h2`, the proxy
forwards H2 frames byte-identically and emits frame-level capture observations
through a Netty HPACK-decoder-driven sniffer.

### Architecture (minimal-parse tee)

The proxy never re-encodes H2 frames; it parses for capture decisions only:

```
client (h2) ─SslHandler─ ApplicationProtocolNegotiationHandler
                              │
                              ├─ http/1.1 → existing H1 pipeline
                              │
                              └─ h2       → H2FrameSnifferHandler ──► PerStreamGateHandler ──► FrontsideHandler ──► upstream (h2)
                                              ↓ (HEADERS decoded, observation emitted)
                                              ↓ (mutating-method gate signals)
                                          capture serializer (Kafka/file)
```

- **`H2FrameSnifferHandler`**: parses the 9-byte frame header, runs an HPACK
  *decoder* (no encoder), emits one `Http2FrameObservation` per frame, forwards
  bytes byte-identically. CONTINUATION fragments are coalesced before decoding.
  See `TrafficCapture/nettyWireLogging/.../H2FrameSnifferHandler.java`.
- **`PerStreamGateHandler`**: holds inbound frames for mutating-method streams
  (POST/PUT/DELETE/PATCH) until the offload commit future resolves, then drains
  the queue in arrival order. Connection-level frames and non-gated streams pass
  through immediately.
- The capture format adds `captureFormatVersion="v2"` and `negotiatedAlpn="h2"`
  to the `TrafficStream` envelope, plus `Http2FrameObservation` and
  `AlpnNegotiationObservation` to the `TrafficObservation` oneof.

### CLI flags

| Flag | Default | Notes |
|------|---------|-------|
| `--enableHttp2` | `false` | Advertise ALPN `h2,http/1.1` on both client and upstream sides; capture H2 frames. |
| `--http2MaxConcurrentStreams` | `100` | Advertised to clients via `SETTINGS_MAX_CONCURRENT_STREAMS`. |
| `--http2InitialWindowSize` | `65535` | Advertised via `SETTINGS_INITIAL_WINDOW_SIZE`; bounds per-stream gate buffering. |
| `--http2MaxHeaderListSize` | `8192` | HPACK decoder cap. |
| `--http2MaxHeaderTableSize` | `4096` | HPACK dynamic-table cap (RFC 7541 default). |

### Constraints

- The proxy does not translate between protocols. Both peers must agree on `h2`
  via ALPN, otherwise the connection falls back to `http/1.1`. If your upstream
  doesn't speak `h2`, leave `--enableHttp2` unset.
- `:method`-based mutating offload-block applies per-stream (not per-connection
  as in H1), so multiplexed GETs are not held by a slow Kafka offload on a
  concurrent POST.
- See `docs/rfcs/0001-http2-trafficcapture.md` for the full spec.
