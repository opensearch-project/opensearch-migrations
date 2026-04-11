# Global ARGs (must be before first FROM to be usable in FROM lines)
ARG BUILDER_IMAGE=docker.io/library/golang:1.24-alpine
ARG RUNTIME_IMAGE=docker.io/library/amazonlinux:2023

# --- Stage 1: Build Go binary ---
FROM ${BUILDER_IMAGE} AS go-builder
ARG TARGETARCH

WORKDIR /build
RUN apk add --no-cache ca-certificates

COPY opensearch-pricing-calculator/go.mod opensearch-pricing-calculator/go.sum ./
RUN go mod download

COPY opensearch-pricing-calculator/ .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} \
    go build -a -installsuffix cgo -o opensearch-pricing-calculator .

# --- Stage 2: Runtime ---
FROM ${RUNTIME_IMAGE}

RUN dnf install -y --setopt=install_weak_deps=False \
      ca-certificates \
      python3.11 \
      python3.11-pip \
      shadow-utils \
    && dnf clean all && rm -rf /var/cache/dnf

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# Go binary and its data
COPY --from=go-builder /build/opensearch-pricing-calculator .
COPY --from=go-builder /build/priceCache.json .

# Version file (staged by Gradle syncVersionFile task)
COPY build/version/VERSION .

# Python skill
COPY skills/solr-opensearch-migration-advisor /app/skills/solr-opensearch-migration-advisor
RUN python3.11 -m pip install --no-cache-dir \
    /app/skills/solr-opensearch-migration-advisor

RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

USER appuser

# Main API port + MCP server port
EXPOSE 5050
EXPOSE 8081

ENTRYPOINT ["/app/opensearch-pricing-calculator"]
