// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

import (
	"context"
	"encoding/json"
	"testing"

	mcpsdk "github.com/mark3labs/mcp-go/mcp"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func newTestToolExecutor(t *testing.T) *ToolExecutor {
	t.Helper()
	logger := zap.NewNop()
	ph := provisioned.NewHandler(logger)
	sh := serverless.NewHandler(logger)
	return NewToolExecutor(logger, ph, sh)
}

// --- ExecuteProvisioned tests ---

func TestExecuteProvisioned_SearchWorkload(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)
	require.NotNil(t, resp)

	// Should have at least one cluster config (limited to 1 by default maxConfigurations)
	assert.Len(t, resp.ClusterConfigs, 1)
	assert.NotNil(t, resp.SearchRequest, "search request should be populated in response")
}

func TestExecuteProvisioned_VectorWorkload(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"vector": map[string]interface{}{
			"vectorCount": 1000000.0,
			"dimensions":  768.0,
			"region":      "US East (N. Virginia)",
		},
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)
	require.NotNil(t, resp)

	assert.Len(t, resp.ClusterConfigs, 1)
	assert.NotNil(t, resp.VectorRequest, "vector request should be populated in response")
}

func TestExecuteProvisioned_TimeSeriesWorkload(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"timeSeries": map[string]interface{}{
			"dailyIngestRateGB": 50.0,
			"retentionDays":     30.0,
			"region":            "US East (N. Virginia)",
		},
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)
	require.NotNil(t, resp)

	assert.Len(t, resp.ClusterConfigs, 1)
	assert.NotNil(t, resp.TimeSeriesRequest, "time series request should be populated in response")
}

// --- maxConfigurations tests ---

func TestExecuteProvisioned_MaxConfigurations_Default(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)

	// Default maxConfigurations is 1
	assert.LessOrEqual(t, len(resp.ClusterConfigs), 1)
}

func TestExecuteProvisioned_MaxConfigurations_Explicit(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
		"maxConfigurations": 2.0, // JSON numbers are float64
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)

	// Should return up to 2 configurations
	assert.LessOrEqual(t, len(resp.ClusterConfigs), 2)
}

func TestExecuteProvisioned_MaxConfigurations_OverCap(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
		"maxConfigurations": 10.0, // Over cap of 3 — should be capped at 3
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)

	// maxConfigurations caps at 3
	assert.LessOrEqual(t, len(resp.ClusterConfigs), 3)
}

func TestExecuteProvisioned_MaxConfigurations_Removed_From_Args(t *testing.T) {
	te := newTestToolExecutor(t)

	// maxConfigurations should be removed from args before passing to handler
	// (it's not a field in EstimateRequest)
	args := map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
		"maxConfigurations": 2.0,
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)
	require.NotNil(t, resp)
}

func TestExecuteProvisioned_StructuredResponse_Stripped(t *testing.T) {
	te := newTestToolExecutor(t)

	// structuredResponse should be stripped from args
	args := map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
		"structuredResponse": true,
	}

	resp, err := te.ExecuteProvisioned(args)
	require.NoError(t, err)
	require.NotNil(t, resp)
}

// --- ExecuteServerless tests ---

func TestExecuteServerless_SearchWorkload(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"search": map[string]interface{}{
			"sizeGB": 100.0,
		},
		"ingest": map[string]interface{}{
			"sizeGB": 10.0,
		},
		"region": "US East (N. Virginia)",
	}

	result, err := te.ExecuteServerless(context.Background(), args)
	require.NoError(t, err)
	require.NotNil(t, result)

	// The result should have pricing information
	assert.Contains(t, result, "price")
}

func TestExecuteServerless_StructuredResponse_Stripped(t *testing.T) {
	te := newTestToolExecutor(t)

	args := map[string]interface{}{
		"search": map[string]interface{}{
			"sizeGB": 100.0,
		},
		"ingest": map[string]interface{}{
			"sizeGB": 10.0,
		},
		"region":             "US East (N. Virginia)",
		"structuredResponse": true,
	}

	result, err := te.ExecuteServerless(context.Background(), args)
	require.NoError(t, err)
	require.NotNil(t, result)
}

// --- CallTool tests ---

func TestCallTool_UnknownTool(t *testing.T) {
	te := newTestToolExecutor(t)

	req := mcpsdk.CallToolRequest{}
	req.Params.Name = "unknown_tool"
	req.Params.Arguments = map[string]interface{}{}

	result, err := te.CallTool(context.Background(), req)
	require.NoError(t, err, "CallTool should not return Go errors for tool failures")
	require.NotNil(t, result)
	assert.True(t, result.IsError, "unknown tool should set IsError")
}

func TestCallTool_ProvisionedEstimate(t *testing.T) {
	te := newTestToolExecutor(t)

	req := mcpsdk.CallToolRequest{}
	req.Params.Name = "provisioned_estimate"
	req.Params.Arguments = map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
	}

	result, err := te.CallTool(context.Background(), req)
	require.NoError(t, err)
	require.NotNil(t, result)
	assert.False(t, result.IsError)

	// Should have content with JSON text
	require.NotEmpty(t, result.Content)
}

func TestCallTool_ServerlessEstimate(t *testing.T) {
	te := newTestToolExecutor(t)

	req := mcpsdk.CallToolRequest{}
	req.Params.Name = "serverless_v2_estimate"
	req.Params.Arguments = map[string]interface{}{
		"search": map[string]interface{}{
			"sizeGB": 100.0,
		},
		"ingest": map[string]interface{}{
			"sizeGB": 10.0,
		},
		"region": "US East (N. Virginia)",
	}

	result, err := te.CallTool(context.Background(), req)
	require.NoError(t, err)
	require.NotNil(t, result)
	assert.False(t, result.IsError)

	require.NotEmpty(t, result.Content)
}

func TestCallTool_ProvisionedEstimate_ReturnsJSON(t *testing.T) {
	te := newTestToolExecutor(t)

	req := mcpsdk.CallToolRequest{}
	req.Params.Name = "provisioned_estimate"
	req.Params.Arguments = map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
	}

	result, err := te.CallTool(context.Background(), req)
	require.NoError(t, err)
	require.NotEmpty(t, result.Content)

	// The first content block should be valid JSON (raw, no markdown)
	textContent, ok := result.Content[0].(mcpsdk.TextContent)
	require.True(t, ok, "content should be TextContent")
	assert.True(t, json.Valid([]byte(textContent.Text)), "content should be valid JSON, got: %s", textContent.Text)
}

func TestCallTool_ServerlessEstimate_ReturnsJSON(t *testing.T) {
	te := newTestToolExecutor(t)

	req := mcpsdk.CallToolRequest{}
	req.Params.Name = "serverless_v2_estimate"
	req.Params.Arguments = map[string]interface{}{
		"search": map[string]interface{}{
			"sizeGB": 100.0,
		},
		"ingest": map[string]interface{}{
			"sizeGB": 10.0,
		},
		"region": "US East (N. Virginia)",
	}

	result, err := te.CallTool(context.Background(), req)
	require.NoError(t, err)
	require.NotEmpty(t, result.Content)

	// The first content block should be valid JSON (raw, no markdown)
	textContent, ok := result.Content[0].(mcpsdk.TextContent)
	require.True(t, ok, "content should be TextContent")
	assert.True(t, json.Valid([]byte(textContent.Text)), "content should be valid JSON, got: %s", textContent.Text)
}

func TestCallTool_ProvisionedEstimate_Error_ReturnsInToolResult(t *testing.T) {
	te := newTestToolExecutor(t)

	// Send an invalid request (both search and vector set)
	req := mcpsdk.CallToolRequest{}
	req.Params.Name = "provisioned_estimate"
	req.Params.Arguments = map[string]interface{}{
		"search": map[string]interface{}{
			"size":   100.0,
			"region": "US East (N. Virginia)",
		},
		"vector": map[string]interface{}{
			"vectorCount": 1000000.0,
			"dimensions":  768.0,
			"region":      "US East (N. Virginia)",
		},
		"timeSeries": map[string]interface{}{
			"dailyIngestRateGB": 50.0,
			"retentionDays":     30.0,
			"region":            "US East (N. Virginia)",
		},
	}

	result, err := te.CallTool(context.Background(), req)
	require.NoError(t, err, "Go error should be nil; error goes in ToolResult")
	require.NotNil(t, result)
	assert.True(t, result.IsError, "validation failure should set IsError in result")
}
