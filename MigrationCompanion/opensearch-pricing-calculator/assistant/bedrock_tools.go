// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"github.com/opensearch-project/opensearch-pricing-calculator/mcp"
)

// GetProvisionedEstimateTool returns the Bedrock tool definition for provisioned estimates
// This uses the actual MCP schema as the single source of truth
func GetProvisionedEstimateTool() map[string]interface{} {
	return map[string]interface{}{
		"name": "provisioned_estimate",
		"description": `Estimate costs for provisioned (managed) OpenSearch clusters.

Use this tool to calculate costs for dedicated OpenSearch clusters with:
- Full-text search workloads
- Vector search with various engine types (HNSW, IVF, etc.)
- Time-series/log analytics data

The tool returns detailed cost breakdowns including:
- Instance costs (data nodes, manager nodes, warm nodes)
- Storage costs (hot, warm, cold tiers)
- Multiple pricing models (OnDemand, Reserved Instances)
- Top 3 most cost-effective cluster configurations

Call this tool with complete configuration parameters. The system will calculate accurate costs based on:
- Workload requirements (size, vector count, retention periods)
- Performance needs (CPUs, memory, replicas)
- Cost optimization preferences (AZs, storage class, instance types)`,
		"input_schema": mcp.ProvisionedRequestSchema,
	}
}

// GetServerlessEstimateTool returns the Bedrock tool definition for serverless estimates
// This uses the actual MCP schema as the single source of truth
func GetServerlessEstimateTool() map[string]interface{} {
	return map[string]interface{}{
		"name": "serverless_v2_estimate",
		"description": `Estimate costs for serverless OpenSearch collections (v2).

Use this tool to calculate costs for serverless OpenSearch with automatic scaling:
- Search workloads
- Vector search
- Time-series/log analytics
- Hybrid workloads (multiple types combined)

The tool returns detailed cost breakdowns including:
- Ingestion OCU costs (OpenSearch Compute Units for indexing)
- Search OCU costs (for query processing)
- Storage costs (S3-based, with hot/warm tiers for time-series)
- OCU-hours per day based on workload patterns

Serverless automatically scales OCUs based on workload. You must provide:
- Ingestion configuration (required for all workloads)
- Optional: search, vector, and/or timeSeries configurations
- Region for pricing
- Redundancy setting (multi-AZ for production)`,
		"input_schema": mcp.ServerlessRequestSchema,
	}
}

// GetAllTools returns all available Bedrock tools for OpenSearch estimation
func GetAllTools() []map[string]interface{} {
	return []map[string]interface{}{
		GetProvisionedEstimateTool(),
		GetServerlessEstimateTool(),
	}
}
