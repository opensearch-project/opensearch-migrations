# H2 Integration Environment

Local docker-compose stack for HTTP/2 capture proxy development and
end-to-end testing.

## What it brings up

- **OpenSearch 2.15** on `localhost:9200` with the netty4 transport
  (which serves HTTP/2 cleartext via h2c when clients use the prior-
  knowledge form). TLS-on H2 testing uses the Capture Proxy's own TLS
  certificates layered in front of this; see
  `TrafficCapture/trafficCaptureProxyServer/PROXY_K8S_INGRESS.md`.

## Usage

```bash
docker compose -f dev-tools/integration/h2/compose.yml up -d
# wait for health
until curl -fs http://localhost:9200/_cluster/health > /dev/null; do sleep 1; done
# H2 cleartext smoke check
curl --http2-prior-knowledge http://localhost:9200/ | head
docker compose -f dev-tools/integration/h2/compose.yml down
```

## Notes

- OpenSearch's HTTP layer accepts HTTP/2 in cleartext mode on the same
  port as HTTP/1.1; ALPN-based H2 over TLS is exercised via the Capture
  Proxy in front of this stack, not OpenSearch directly.
- The security plugin is disabled here for simplicity. Production
  scenarios with TLS-terminated H2 are exercised by the Capture Proxy's
  integration tests.
