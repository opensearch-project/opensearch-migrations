// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

import (
	"context"
	"encoding/json"
	"fmt"

	mcpsdk "github.com/mark3labs/mcp-go/mcp"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"go.uber.org/zap"
)

// ToolExecutor provides two-layer tool execution:
// - Inner layer: ExecuteProvisioned / ExecuteServerless return native Go types for in-process callers
// - Outer layer: CallTool wraps results into MCP wire format for transports
type ToolExecutor struct {
	logger             *zap.Logger
	provisionedHandler *provisioned.Handler
	serverlessHandler  *serverless.Handler
}

// NewToolExecutor creates a new ToolExecutor with the given dependencies.
func NewToolExecutor(logger *zap.Logger, provisionedHandler *provisioned.Handler, serverlessHandler *serverless.Handler) *ToolExecutor {
	return &ToolExecutor{
		logger:             logger,
		provisionedHandler: provisionedHandler,
		serverlessHandler:  serverlessHandler,
	}
}

// ExecuteProvisioned runs a provisioned estimate calculation and returns the native response.
// It handles: parse args -> extract maxConfigurations -> apply defaults -> validate -> normalize -> calculate -> limit results.
func (te *ToolExecutor) ExecuteProvisioned(args map[string]interface{}) (*provisioned.EstimateResponse, error) {
	te.logger.Info("executing provisioned estimate", zap.Any("args", args))

	// Extract and remove maxConfigurations from args (not a field in EstimateRequest)
	maxConfigs := extractMaxConfigurations(args)

	// Remove structuredResponse if present (being deprecated)
	delete(args, "structuredResponse")

	// Convert args to JSON bytes for processing
	requestBytes, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal arguments: %w", err)
	}

	// Parse into EstimateRequest
	var request provisioned.EstimateRequest
	if err := json.Unmarshal(requestBytes, &request); err != nil {
		return nil, fmt.Errorf("failed to unmarshal request: %w", err)
	}

	// Validate the request
	if err := request.Validate(); err != nil {
		return nil, fmt.Errorf("request validation failed: %w", err)
	}

	// Apply defaults based on workload type
	if request.Search != nil {
		request = createSearchRequestWithDefaults(requestBytes)
	} else if request.Vector != nil {
		request = createVectorRequestWithDefaults(requestBytes)
	} else {
		request = createTimeSeriesRequestWithDefaults(requestBytes)
	}

	// Normalize the request
	request.Normalize(te.logger)

	// Calculate the estimate
	result, err := request.Calculate()
	if err != nil {
		return nil, fmt.Errorf("calculation failed: %w", err)
	}

	// Limit cluster configurations
	if len(result.ClusterConfigs) > maxConfigs {
		result.ClusterConfigs = result.ClusterConfigs[:maxConfigs]
	}

	return &result, nil
}

// ExecuteServerless runs a serverless estimate calculation and returns the result as a map.
func (te *ToolExecutor) ExecuteServerless(ctx context.Context, args map[string]interface{}) (map[string]interface{}, error) {
	te.logger.Info("executing serverless estimate", zap.Any("args", args))

	// Remove structuredResponse if present (being deprecated)
	delete(args, "structuredResponse")

	// Forward to the serverless handler
	result, err := te.serverlessHandler.Handle(ctx, args)
	if err != nil {
		return nil, fmt.Errorf("serverless calculation failed: %w", err)
	}

	// Convert the result to map[string]interface{} via JSON round-trip
	resultBytes, err := json.Marshal(result)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal serverless result: %w", err)
	}

	var resultMap map[string]interface{}
	if err := json.Unmarshal(resultBytes, &resultMap); err != nil {
		return nil, fmt.Errorf("failed to unmarshal serverless result to map: %w", err)
	}

	return resultMap, nil
}

// CallTool dispatches a tool call by name and returns the result in MCP wire format.
// This matches the mcp-go SDK handler signature.
// Errors from tool execution are returned inside the CallToolResult (IsError=true),
// not as Go errors — so the LLM can see and self-correct.
func (te *ToolExecutor) CallTool(ctx context.Context, request mcpsdk.CallToolRequest) (*mcpsdk.CallToolResult, error) {
	args := request.GetArguments()

	switch request.Params.Name {
	case "provisioned_estimate":
		resp, err := te.ExecuteProvisioned(args)
		if err != nil {
			return mcpsdk.NewToolResultError(err.Error()), nil
		}
		resultJSON, err := json.Marshal(resp)
		if err != nil {
			return mcpsdk.NewToolResultErrorf("failed to marshal result: %v", err), nil
		}
		return mcpsdk.NewToolResultText(string(resultJSON)), nil

	case "serverless_v2_estimate":
		resp, err := te.ExecuteServerless(ctx, args)
		if err != nil {
			return mcpsdk.NewToolResultError(err.Error()), nil
		}
		resultJSON, err := json.Marshal(resp)
		if err != nil {
			return mcpsdk.NewToolResultErrorf("failed to marshal result: %v", err), nil
		}
		return mcpsdk.NewToolResultText(string(resultJSON)), nil

	default:
		return mcpsdk.NewToolResultErrorf("unknown tool: %s", request.Params.Name), nil
	}
}

// extractMaxConfigurations extracts and removes the maxConfigurations parameter from args.
// Defaults to 1, caps at 3.
func extractMaxConfigurations(args map[string]interface{}) int {
	maxConfigs := 1

	if mc, ok := args["maxConfigurations"]; ok {
		delete(args, "maxConfigurations")
		switch v := mc.(type) {
		case float64:
			maxConfigs = int(v)
		case int:
			maxConfigs = v
		case json.Number:
			if n, err := v.Int64(); err == nil {
				maxConfigs = int(n)
			}
		}
	}

	if maxConfigs < 1 {
		maxConfigs = 1
	}
	if maxConfigs > 3 {
		maxConfigs = 3
	}

	return maxConfigs
}
