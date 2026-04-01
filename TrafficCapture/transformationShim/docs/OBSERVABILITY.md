# ShimProxy Observability

The ShimProxy emits OpenTelemetry traces and metrics for every proxied request. Instrumentation follows the `coreUtilities` framework patterns and is controlled via a single CLI flag.

## Enabling Telemetry

Pass `--otelCollectorEndpoint` to point at any OTLP gRPC receiver:

```
--otelCollectorEndpoint http://localhost:4317
```

When omitted, instrumentation runs in no-op mode with zero overhead.

## Trace Structure

Each incoming HTTP request produces a trace tree:

```
shimRequest (top-level)
├── targetDispatch:solr
├── targetDispatch:opensearch
│   ├── transform:request
│   └── transform:response
```

Transform spans only appear when a target has JavaScript transforms configured.

## Span Attributes

| Span | Attribute | Description |
|---|---|---|
| shimRequest | `http.method` | HTTP method (GET, POST, etc.) |
| shimRequest | `http.url` | Request URI path |
| targetDispatch | `target.name` | Target name (e.g., "solr", "opensearch") |
| targetDispatch | `http.status_code` | Backend response status code |
| targetDispatch | `targetBytesSent` | Request body bytes sent to target |
| targetDispatch | `targetBytesReceived` | Response body bytes received |
| transform | `transform.direction` | "request" or "response" |
| transform | `target.name` | Parent target name |

Parent attributes (`http.method`, `http.url`) propagate to all child spans.

## Metrics

Auto-emitted by the framework on span close:

| Metric | Type | Description |
|---|---|---|
| `shimRequestCount` | Counter | Requests processed |
| `shimRequestDuration` | Histogram (ms) | End-to-end request duration |
| `shimRequestExceptionCount` | Counter | Request-level exceptions |
| `targetDispatchCount` | Counter | Per-target dispatches |
| `targetDispatchDuration` | Histogram (ms) | Per-target round-trip time |
| `targetDispatchExceptionCount` | Counter | Per-target failures |
| `transformCount` | Counter | Transform executions |
| `transformDuration` | Histogram (ms) | GraalJS execution time |
| `transformExceptionCount` | Counter | Transform failures |

Custom counters:

| Metric | Type | Description |
|---|---|---|
| `targetBytesSent` | Counter (bytes) | Bytes sent to each target |
| `targetBytesReceived` | Counter (bytes) | Bytes received from each target |

### Metric Attributes

Low-cardinality attributes are attached to metrics for aggregation:

- `targetDispatch*` metrics include `target.name` and `http.status_code`
- `transform*` metrics include `target.name` and `transform.direction`

## Local Testing

The `solrShimTestHarness` docker-compose includes a Grafana LGTM stack:

```bash
cd solrShimTestHarness && ./run.sh
```

- Grafana UI: http://localhost:3000 (admin/admin)
- Traces: Explore → Tempo datasource → search service "shimProxy"
- Metrics: Explore → Prometheus datasource → query `shimRequestCount`, `targetDispatchDuration_bucket`, etc.
