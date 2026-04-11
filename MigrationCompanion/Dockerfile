# --- Stage 1: Build Go binary ---
FROM golang:1.24-alpine AS go-builder
ARG TARGETARCH

WORKDIR /build
RUN apk add --no-cache ca-certificates

COPY opensearch-pricing-calculator/go.mod opensearch-pricing-calculator/go.sum ./
RUN go mod download

COPY opensearch-pricing-calculator/ .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} \
    go build -a -installsuffix cgo -o opensearch-pricing-calculator .

# --- Stage 2: Runtime ---
FROM alpine:3.21

RUN apk add --no-cache ca-certificates python3 py3-pip && \
    update-ca-certificates && rm -rf /var/cache/apk/*

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Go binary and its data
COPY --from=go-builder /build/opensearch-pricing-calculator .
COPY --from=go-builder /build/priceCache.json .

# Version file (staged by Gradle syncVersionFile task)
COPY build/version/VERSION .

# Python skill
COPY skills/solr-opensearch-migration-advisor /app/skills/solr-opensearch-migration-advisor
RUN pip install --no-cache-dir --break-system-packages \
    /app/skills/solr-opensearch-migration-advisor

RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

USER appuser

# Main API port + MCP server port
EXPOSE 5050
EXPOSE 8081

ENTRYPOINT ["/app/opensearch-pricing-calculator"]
