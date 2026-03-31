# OpenSearch Pricing Calculator

Cost estimation API for [Amazon OpenSearch Service](https://aws.amazon.com/opensearch-service/) managed clusters and serverless collections.

## Features

- **Managed Cluster Estimation** — Right-size instances, storage, and pricing tier for search, time-series, and vector workloads
- **Serverless Estimation** — Estimate OCU consumption and storage costs for OpenSearch Serverless collections
- **Vector Search Support** — HNSW, IVF, and on-disk compression modes (2x–32x) with accurate memory/storage modeling
- **AI Assistant** — Natural language cost estimation powered by AWS Bedrock (optional)
- **MCP Server** — [Model Context Protocol](https://modelcontextprotocol.io/) endpoint for IDE and AI agent integration
- **Observability** — OpenTelemetry traces, Prometheus metrics, structured logging (Zap)

## Quick Start

### Prerequisites

- Go 1.24+
- (Optional) AWS credentials for Bedrock-powered AI assistant

### Build and Run

```bash
go mod download
go build -o opensearch-pricing-calculator .
./opensearch-pricing-calculator
```

The service starts two servers:
- **HTTP API** on port `5050`
- **MCP Server** on port `8081`

### Docker

```bash
docker build -t opensearch-pricing-calculator .
docker run -p 5050:5050 -p 8081:8081 opensearch-pricing-calculator
```

## API Endpoints

### Managed Cluster Estimation

```
POST /provisioned/estimate
```

Estimates infrastructure costs for a managed OpenSearch domain. The request body specifies one workload type: `search`, `timeSeries`, or `vector`.

**Search workload example:**

```bash
curl -X POST http://localhost:5050/provisioned/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "search": {
      "size": 200,
      "azs": 3,
      "replicas": 1,
      "targetShardSize": 25,
      "CPUsPerShard": 1.5,
      "pricingType": "OnDemand",
      "region": "US East (N. Virginia)"
    }
  }'
```

**Time-series workload example:**

```bash
curl -X POST http://localhost:5050/provisioned/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "timeSeries": {
      "size": 500,
      "azs": 3,
      "replicas": 1,
      "hotRetentionPeriod": 14,
      "warmRetentionPeriod": 76,
      "targetShardSize": 45,
      "CPUsPerShard": 1.25,
      "pricingType": "OnDemand",
      "region": "US East (N. Virginia)"
    }
  }'
```

**Vector workload example:**

```bash
curl -X POST http://localhost:5050/provisioned/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "vector": {
      "vectorCount": 10000000,
      "dimensionsCount": 768,
      "vectorEngineType": "hnswfp16",
      "maxEdges": 16,
      "azs": 3,
      "replicas": 1,
      "pricingType": "OnDemand",
      "region": "US East (N. Virginia)"
    }
  }'
```

### Serverless Estimation

```
POST /serverless/v2/estimate
```

Estimates costs for OpenSearch Serverless collections. Supports `timeSeries`, `search`, and `vector` collection types.

```bash
curl -X POST http://localhost:5050/serverless/v2/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "timeSeries": {
      "dailyIndexSize": 10,
      "daysInHot": 1,
      "daysInWarm": 6,
      "minQueryRate": 1,
      "maxQueryRate": 1,
      "hoursAtMaxRate": 0
    },
    "region": "us-east-1",
    "redundancy": true
  }'
```

### Reference Data

```
GET /provisioned/regions              # Available regions (managed)
GET /provisioned/price                # Current pricing data (managed)
GET /provisioned/pricingOptions       # Pricing tier options (OnDemand, RI)
GET /provisioned/instanceFamilyOptions/{region}  # Instance families by region
GET /serverless/regions               # Available regions (serverless)
GET /serverless/price                 # Current pricing data (serverless)
```

### Operations

```
GET  /health                          # Health check
GET  /metrics                         # Prometheus metrics
GET  /swagger/*                       # Swagger UI
POST /provisioned/cache/invalidate    # Refresh managed pricing cache
POST /serverless/cache/invalidate     # Refresh serverless pricing cache
```

### AI Assistant (Optional)

```
POST /api/assistant/estimate          # Natural language cost estimation
```

Requires AWS Bedrock credentials. See [Configuration](#configuration).

## Project Structure

```
├── main.go                  # Application entry point
├── routes.go                # HTTP route definitions
├── handler.go               # Request handlers
├── middleware.go             # CORS, rate limiting, auth
├── impl/
│   ├── provisioned/         # Managed cluster calculations
│   ├── serverless/          # Serverless collection calculations
│   ├── cache/               # AWS pricing data cache
│   ├── instances/           # Instance type definitions
│   └── regions/             # Region mappings
├── assistant/               # NLP parsing + Bedrock LLM integration
├── mcp/                     # Model Context Protocol server
├── telemetry/               # OpenTelemetry setup
├── scheduler/               # Background task scheduling
├── docs/                    # Swagger/OpenAPI specs (auto-generated)
└── Dockerfile               # Multi-stage production build
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `5050` | HTTP API port |
| `MCP_PORT` | `8081` | MCP server port |
| `ENVIRONMENT` | auto-detected | `local`, `ecs`, or custom |
| `AWS_REGION` | `us-west-2` | AWS region for Bedrock (assistant only) |
| `AWS_PROFILE` | — | AWS profile for local development |
| `MCP_BASE_URL` | `http://localhost:8081` | Base URL for internal MCP client |
| `ALLOWED_ORIGINS` | built-in list | Comma-separated additional CORS origins |
| `RATE_LIMIT_GENERAL` | `100` | General API rate limit (requests per minute per IP) |
| `RATE_LIMIT_SENSITIVE` | `10` | Sensitive operations rate limit (requests per minute per IP) |
| `USE_TOOL_CALLING` | `false` | Enable Bedrock Tool Calling API for assistant |
| `FORCE_LLM_PATH` | `false` | Force LLM path for all assistant queries |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | OpenTelemetry collector endpoint |
| `OTEL_EXPORTER_OTLP_INSECURE` | `false` | Disable TLS for OTel exporter |

### Authentication

This service does not implement authentication directly. For production deployments, place it behind an authenticating reverse proxy such as API Gateway with IAM authorization, ALB with OIDC, or CloudFront with Lambda@Edge. See [SECURITY.md](SECURITY.md) for details.

### Pricing Cache

On first request, the service downloads pricing data from the AWS Pricing API and caches it locally in `priceCache.json`. To refresh:

```bash
# Refresh all regions (takes a few minutes)
curl -X POST http://localhost:5050/provisioned/cache/invalidate?update=true

# Refresh a single region (faster)
curl -X POST "http://localhost:5050/provisioned/cache/invalidate?update=true&region=US%20East%20(N.%20Virginia)"
```

## Development

### Run Tests

```bash
go test ./...
go test -v -cover ./...
```

### Regenerate Swagger Docs

```bash
go install github.com/swaggo/swag/cmd/swag@latest
swag init
```

### Local Build Script

```bash
./build.sh   # Builds binary + Docker image
```

## MCP Server

The calculator exposes an MCP endpoint on port 8081 with two tools:

- `provisioned_estimate` — Managed cluster cost estimation
- `serverless_estimate` — Serverless collection cost estimation

Compatible with Claude Desktop, Cline, Continue.dev, and other MCP clients.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding guidelines, and pull request process.

## Security

See [SECURITY.md](SECURITY.md) for the vulnerability disclosure policy and deployment best practices.

## License

This project is licensed under the [Apache License 2.0](LICENSE).
