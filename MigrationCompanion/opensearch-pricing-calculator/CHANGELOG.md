# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `SECURITY.md` with vulnerability disclosure policy and deployment best practices
- `CONTRIBUTING.md` with development setup and contribution guidelines
- `CODE_OF_CONDUCT.md`
- Comprehensive `README.md` with API documentation and usage examples
- Request body size limit (1 MB) to prevent memory exhaustion
- HTTP client timeouts (30 min) on all outbound pricing API requests
- Error handling for JSON marshal failures in cache file writes
- SPDX license headers on all Go source files

### Changed
- Replaced `yalp/jsonpath` with actively maintained `PaesslerAG/jsonpath`
- CI/CD pipeline: parameterized AWS account ID, region, and environment URLs
- CI/CD pipeline: aligned build architecture to `amd64` (matching Dockerfile and build.sh)
- CI/CD pipeline: removed debug build flags (`-gcflags "all=-N -l"`)
- Swagger metadata updated for open-source (contact, title, description)
- Sanitized example files: removed internal API Gateway and ELB URLs

### Removed
- Deprecated `vectorengine/` package (replaced by `vectorV2.go`)
- Deprecated V1 serverless calculation paths and routes
- Internal email addresses and AWS profile references

### Fixed
- Silent error handling in `ServerlessEstimateV2()` — now returns proper JSON error responses
- Ignored `json.Marshal` errors in all HTTP handlers — added `writeJSON` helper
- Ignored `json.MarshalIndent` errors in cache file writes

## [2.0.0] - Unreleased

### Added
- User profiles with DynamoDB persistence
- Opportunity CRUD API with pagination and search
- Estimate persistence with compare and soft-delete
- Sharing API with public link resolution
- AI assistant with natural language cost estimation (AWS Bedrock)
- MCP (Model Context Protocol) server for IDE integration
- What-If analysis for serverless workloads
- OpenTelemetry tracing and Prometheus metrics
- Structured logging with Zap
- Rate limiting (general, sensitive, cache invalidation tiers)
- CORS origin validation with allowlist
