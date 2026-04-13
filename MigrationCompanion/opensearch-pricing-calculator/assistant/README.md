# Assistant Package - Go Implementation

## Overview
This package provides intelligent natural language query parsing and LLM-enhanced parameter generation for OpenSearch cost estimation.

## Architecture

```
assistant/
├── types.go              ✅ Data structures
├── nlp_parser.go         🔄 NLP query parsing (port from Node.js)
├── llm_enhancer.go       🔄 AWS Bedrock integration
├── mcp_client.go         🔄 MCP server client
├── response_formatter.go 🔄 Response formatting
├── handler.go            🔄 HTTP handler
└── cache.go              🔄 In-memory cache
```

## Implementation Status

### ✅ Completed
- [x] Package structure
- [x] Type definitions

### 🔄 In Progress
- [ ] NLP parser (regex-based extraction)
- [ ] AWS Bedrock LLM client
- [ ] MCP client (calls port 8081)
- [ ] Response formatter
- [ ] HTTP handler
- [ ] Caching layer

## Integration Points

### Frontend
- **Endpoint**: `POST /api/assistant/estimate`
- **Request**: `{"query": "Vector search with 10M vectors, 768 dimensions"}`
- **Response**: Structured data with managed/serverless estimates

### Backend Services
- **MCP Server**: `http://localhost:8081/mcp/v1` (already running)
- **AWS Bedrock**: Region `us-west-2` (configurable via `AWS_REGION`), Model configurable in `llm_enhancer.go`

## Dependencies

```bash
# AWS SDK for Bedrock
go get github.com/aws/aws-sdk-go-v2/config
go get github.com/aws/aws-sdk-go-v2/service/bedrockruntime

# HTTP client
# (use existing net/http)
```

## Usage

```go
// In routes.go
r.Post("/api/assistant/estimate", assistantHandler.HandleEstimate)

// Initialize handler
assistantHandler := assistant.NewHandler()
```

## Flow

1. **NLP Parse** → Extract parameters from natural language
2. **LLM Enhance** (optional) → Use Bedrock if confidence < 0.7
3. **MCP Call** → Request estimates for all pricing types
4. **Format** → Structure response for frontend
5. **Cache** → Store for 5 minutes

## Configuration

Environment variables (already in ECS):
- `AWS_REGION=us-west-2`
- `AWS_PROFILE=<your-profile>` (local dev only)
- `MCP_BASE_URL=http://localhost:8081`

## Next Steps

1. Complete NLP parser implementation
2. Add AWS Bedrock client
3. Implement MCP client
4. Create response formatter
5. Wire up HTTP handler
6. Add to routes.go
7. Test locally
8. Deploy via `./cdk-deployment/deploy.sh`
