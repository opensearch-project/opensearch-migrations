FROM golang:1.26-alpine AS builder

WORKDIR /build

# Install ca-certificates for HTTPS
RUN apk add --no-cache ca-certificates

# Cache dependencies
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build static binary with version info
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -a -installsuffix cgo -o opensearch-pricing-calculator .

# --- Runtime stage ---
FROM alpine:3.21

RUN apk add --no-cache ca-certificates && update-ca-certificates \
    && rm -rf /var/cache/apk/*

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /build/opensearch-pricing-calculator .
COPY --from=builder /build/priceCache.json .

# Create logs directory owned by non-root user
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

# Drop to non-root user
USER appuser

# Main API port + MCP server port
EXPOSE 5050
EXPOSE 8081

ENTRYPOINT ["/app/opensearch-pricing-calculator"]
