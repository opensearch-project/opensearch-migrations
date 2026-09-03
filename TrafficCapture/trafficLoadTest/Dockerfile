# Load-test-only k6 runner. xk6 extensions are compiled into the k6 executable; they cannot be
# loaded from JavaScript at runtime. Keep both versions pinned so rebuilding this image is the only
# way its executable changes.
FROM grafana/xk6:1.4.3 AS builder

# Unset (and empty) means the Go default, https://proxy.golang.org,direct. Networks that block the
# public Go module proxy supply their own mirror, so no mirror is named here: `docker build
# --build-arg GOPROXY` with no value passes the builder's own GOPROXY through, keeping the value in
# the environment that needs it rather than in this repository.
#
# GOPROXY=direct is not a substitute for a mirror. Transitive dependencies whose upstream
# repositories have disappeared (github.com/Soontao/goHttpDigestClient) survive only as module-proxy
# caches, so a direct fetch fails. Whatever proxy serves the modules, GONOSUMDB stays unset and
# sum.golang.org still verifies their checksums.
ARG GOPROXY

RUN xk6 build v2.2.0 \
      --with github.com/grafana/xk6-redis@v0.4.1 \
      --output /tmp/k6

FROM grafana/k6:2.2.0

COPY --from=builder /tmp/k6 /usr/bin/k6

# Automatic extension resolution would download a different k6 executable when an imported
# extension is absent. This image is intentionally complete and must fail instead of reaching out.
ENV K6_AUTO_EXTENSION_RESOLUTION=false

# Copy scripts after the compiled executable so ordinary scenario edits retain the expensive xk6
# build layers.
COPY scenarios/ /scripts/scenarios/
COPY lib/       /scripts/lib/
COPY k6-config/ /scripts/k6-config/
