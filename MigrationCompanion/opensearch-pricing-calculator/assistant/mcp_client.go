// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
)

const methodToolsCall = "tools/call"

// MCPClient handles communication with the MCP server
type MCPClient struct {
	baseURL    string
	httpClient *http.Client
}

// NewMCPClient creates a new MCP client instance
func NewMCPClient() *MCPClient {
	baseURL := os.Getenv("MCP_BASE_URL")
	if baseURL == "" {
		baseURL = "http://localhost:8081"
	}

	return &MCPClient{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

// GetEstimates calls MCP server to get both managed and serverless estimates
func (c *MCPClient) GetEstimates(ctx context.Context, params *ParsedQuery) (map[string]interface{}, map[string]interface{}, error) {
	var managedResult, serverlessResult map[string]interface{}
	var managedErr, serverlessErr error

	// Call both endpoints in parallel
	done := make(chan bool, 2)

	// Get managed estimate
	go func() {
		managedResult, managedErr = c.getManagedEstimate(ctx, params)
		done <- true
	}()

	// Get serverless estimate
	go func() {
		serverlessResult, serverlessErr = c.getServerlessEstimate(ctx, params)
		done <- true
	}()

	// Wait for both to complete
	<-done
	<-done

	// Return error only if both failed
	if managedErr != nil && serverlessErr != nil {
		return nil, nil, fmt.Errorf("both MCP calls failed: managed=%v, serverless=%v", managedErr, serverlessErr)
	}

	return managedResult, serverlessResult, nil
}

// GetEstimatesFromEnhanced calls MCP server with pre-built request payloads from LLM
// This bypasses the buildArgs functions and uses complete MCP requests from the LLM
func (c *MCPClient) GetEstimatesFromEnhanced(ctx context.Context, enhanced *EnhancedLLMResponse) (map[string]interface{}, map[string]interface{}, error) {
	var managedResult, serverlessResult map[string]interface{}
	var managedErr, serverlessErr error

	// Call endpoints based on deployment preference
	done := make(chan bool, 2)
	needManaged := enhanced.DeploymentPreference == "managed" || enhanced.DeploymentPreference == "both"
	needServerless := enhanced.DeploymentPreference == "serverless" || enhanced.DeploymentPreference == "both"

	// Get managed estimate
	if needManaged {
		go func() {
			managedResult, managedErr = c.callMCPWithArgs(ctx, "provisioned_estimate", enhanced.ManagedRequest)
			done <- true
		}()
	} else {
		go func() {
			done <- true
		}()
	}

	// Get serverless estimate
	if needServerless {
		go func() {
			serverlessResult, serverlessErr = c.callMCPWithArgs(ctx, "serverless_v2_estimate", enhanced.ServerlessRequest)
			done <- true
		}()
	} else {
		go func() {
			done <- true
		}()
	}

	// Wait for both to complete
	<-done
	<-done

	// Return error only if both failed
	if managedErr != nil && serverlessErr != nil {
		return nil, nil, fmt.Errorf("both MCP calls failed: managed=%v, serverless=%v", managedErr, serverlessErr)
	}

	return managedResult, serverlessResult, nil
}

// callMCPWithArgs calls MCP with pre-built arguments
func (c *MCPClient) callMCPWithArgs(ctx context.Context, toolName string, args map[string]interface{}) (map[string]interface{}, error) {
	if len(args) == 0 {
		return nil, nil // Skip if no args provided
	}

	payload := MCPPayload{
		JSONRPC: "2.0",
		Method:  methodToolsCall,
		ID:      uuid.New().String(),
		Params: MCPParams{
			Name:      toolName,
			Arguments: args,
		},
	}

	return c.callMCP(ctx, payload)
}

func (c *MCPClient) getManagedEstimate(ctx context.Context, params *ParsedQuery) (map[string]interface{}, error) {
	// Build arguments for provisioned estimate
	args := c.buildProvisionedArgs(params)

	payload := MCPPayload{
		JSONRPC: "2.0",
		Method:  methodToolsCall,
		ID:      uuid.New().String(),
		Params: MCPParams{
			Name:      "provisioned_estimate",
			Arguments: args,
		},
	}

	return c.callMCP(ctx, payload)
}

func (c *MCPClient) getServerlessEstimate(ctx context.Context, params *ParsedQuery) (map[string]interface{}, error) {
	// Build arguments for serverless estimate
	args := c.buildServerlessArgs(params)

	payload := MCPPayload{
		JSONRPC: "2.0",
		Method:  methodToolsCall,
		ID:      uuid.New().String(),
		Params: MCPParams{
			Name:      "serverless_v2_estimate",
			Arguments: args,
		},
	}

	return c.callMCP(ctx, payload)
}

func (c *MCPClient) buildProvisionedArgs(params *ParsedQuery) map[string]interface{} {
	workloadConfig := make(map[string]interface{})

	// Common parameters
	workloadConfig["region"] = params.Region
	if params.Size > 0 {
		workloadConfig["size"] = params.Size
	}

	// Workload-specific parameters
	switch params.WorkloadType {
	case "vector":
		addProvisionedVectorParams(workloadConfig, params)
	case "timeseries":
		addProvisionedTimeSeriesParams(workloadConfig, params)
	case "search":
		addProvisionedSearchParams(workloadConfig, params)
	}

	if params.MultiAzWithStandby {
		workloadConfig["multiAzWithStandby"] = params.MultiAzWithStandby
	}

	workloadKey := params.WorkloadType
	if workloadKey == "timeseries" {
		workloadKey = "timeSeries"
	}

	return map[string]interface{}{
		workloadKey:          workloadConfig,
		"structuredResponse": true,
	}
}

// addProvisionedVectorParams adds vector-specific parameters to the provisioned workload config.
func addProvisionedVectorParams(config map[string]interface{}, params *ParsedQuery) {
	if params.VectorCount > 0 {
		config["vectorCount"] = params.VectorCount
	}
	if params.Dimensions > 0 {
		config["dimensionsCount"] = params.Dimensions
	}
	if params.VectorEngine != "" {
		config["vectorEngineType"] = params.VectorEngine
	}
	if params.OnDisk {
		config["onDisk"] = params.OnDisk
		if params.CompressionLevel > 0 {
			config["compressionLevel"] = params.CompressionLevel
		}
	}
	addStorageCompressionParams(config, params)
	addWarmColdTierParams(config, params)
}

// addProvisionedTimeSeriesParams adds timeseries-specific parameters to the provisioned workload config.
func addProvisionedTimeSeriesParams(config map[string]interface{}, params *ParsedQuery) {
	hotPeriod := params.HotPeriod
	if hotPeriod == 0 {
		hotPeriod = 7
	}
	config["hotRetentionPeriod"] = hotPeriod

	if params.WarmPeriod > 0 {
		config["warmRetentionPeriod"] = params.WarmPeriod
	}
	if params.ColdPeriod > 0 {
		config["coldRetentionPeriod"] = params.ColdPeriod
	}
	addWarmInstanceParams(config, params)
	addStorageCompressionParams(config, params)
}

// addProvisionedSearchParams adds search-specific parameters to the provisioned workload config.
func addProvisionedSearchParams(config map[string]interface{}, params *ParsedQuery) {
	addStorageCompressionParams(config, params)
	addWarmColdTierParams(config, params)
}

// addStorageCompressionParams adds derivedSource and zstdCompression to a config map.
func addStorageCompressionParams(config map[string]interface{}, params *ParsedQuery) {
	if params.DerivedSource {
		config["derivedSource"] = params.DerivedSource
	}
	if params.ZstdCompression {
		config["zstdCompression"] = params.ZstdCompression
	}
}

// addWarmColdTierParams adds warm/cold tier and warm instance parameters to a config map.
func addWarmColdTierParams(config map[string]interface{}, params *ParsedQuery) {
	if params.WarmPercentage > 0 {
		config["warmPercentage"] = params.WarmPercentage
	}
	if params.ColdPercentage > 0 {
		config["coldPercentage"] = params.ColdPercentage
	}
	addWarmInstanceParams(config, params)
}

// addWarmInstanceParams adds warm instance type and auto-select parameters to a config map.
func addWarmInstanceParams(config map[string]interface{}, params *ParsedQuery) {
	if params.WarmInstanceType != "" {
		config["warmInstanceType"] = params.WarmInstanceType
	}
	if params.AutoSelectWarmInstance != nil {
		config["autoSelectWarmInstance"] = *params.AutoSelectWarmInstance
	}
}

func (c *MCPClient) buildServerlessArgs(params *ParsedQuery) map[string]interface{} {
	args := map[string]interface{}{
		"region": params.Region,
		"ingest": map[string]interface{}{
			"minIndexingRate": 0.1,
			"maxIndexingRate": 1.0,
			"timePerDayAtMax": 8,
		},
		"structuredResponse": true,
	}

	switch params.WorkloadType {
	case "vector":
		args["vector"] = buildServerlessVectorConfig(params)
	case "search":
		args["search"] = buildServerlessSearchConfig(params)
	case "timeseries":
		args["timeSeries"] = buildServerlessTimeSeriesConfig(params)
	}

	return args
}

// buildServerlessVectorConfig builds the vector workload config for serverless requests.
func buildServerlessVectorConfig(params *ParsedQuery) map[string]interface{} {
	config := make(map[string]interface{})
	if params.Size > 0 {
		config["size"] = params.Size
	}
	if params.VectorCount > 0 {
		config["vectorCount"] = params.VectorCount
	}
	if params.Dimensions > 0 {
		config["dimensionsCount"] = params.Dimensions
	}
	if params.VectorEngine != "" {
		config["vectorEngineType"] = params.VectorEngine
	}
	if params.OnDisk {
		config["onDisk"] = params.OnDisk
		if params.CompressionLevel > 0 {
			config["compressionLevel"] = params.CompressionLevel
		}
	}
	addStorageCompressionParams(config, params)
	return config
}

// buildServerlessSearchConfig builds the search workload config for serverless requests.
func buildServerlessSearchConfig(params *ParsedQuery) map[string]interface{} {
	config := make(map[string]interface{})
	if params.Size > 0 {
		config["collectionSize"] = params.Size
	}
	addStorageCompressionParams(config, params)
	return config
}

// buildServerlessTimeSeriesConfig builds the timeSeries workload config for serverless requests.
func buildServerlessTimeSeriesConfig(params *ParsedQuery) map[string]interface{} {
	config := make(map[string]interface{})
	if params.Size > 0 {
		config["dailyIndexSize"] = params.Size / 30
	}

	hotPeriod := params.HotPeriod
	if hotPeriod == 0 {
		hotPeriod = 1
	}
	config["daysInHot"] = hotPeriod

	if params.WarmPeriod > 0 {
		config["daysInWarm"] = params.WarmPeriod
	}
	addStorageCompressionParams(config, params)
	return config
}

func (c *MCPClient) callMCP(ctx context.Context, payload MCPPayload) (map[string]interface{}, error) {
	// Marshal payload
	payloadJSON, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal MCP payload: %w", err)
	}

	// Create request
	req, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/mcp", bytes.NewBuffer(payloadJSON))
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")

	// Execute request
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to call MCP server: %w", err)
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	// Check status code
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("MCP server returned status %d: %s", resp.StatusCode, string(body))
	}

	// Parse response
	var mcpResp MCPResponse
	if err := json.Unmarshal(body, &mcpResp); err != nil {
		return nil, fmt.Errorf("failed to unmarshal MCP response: %w", err)
	}

	// Check for errors in response
	if mcpResp.Error != nil {
		return nil, fmt.Errorf("MCP error: %s (code %d)", mcpResp.Error.Message, mcpResp.Error.Code)
	}

	return mcpResp.Result, nil
}

// ExtractEstimateData extracts the actual estimate data from MCP result
func (c *MCPClient) ExtractEstimateData(result map[string]interface{}) (map[string]interface{}, error) {
	if result == nil {
		return nil, fmt.Errorf("nil result")
	}

	content, ok := result["content"].([]interface{})
	if !ok || len(content) == 0 {
		return result, nil
	}

	// First pass: look for structured JSON data
	if data := extractJSONFromContent(content); data != nil {
		return data, nil
	}

	// Second pass: collect markdown text
	if text := extractMarkdownFromContent(content); text != "" {
		return map[string]interface{}{
			"description": text,
		}, nil
	}

	return result, nil
}

// extractJSONFromContent searches content blocks for structured JSON data.
func extractJSONFromContent(content []interface{}) map[string]interface{} {
	for _, item := range content {
		text := extractTextFromContentItem(item)
		if text == "" {
			continue
		}

		if data := tryExtractJSONFromCodeBlock(text); data != nil {
			return data
		}

		var data map[string]interface{}
		if err := json.Unmarshal([]byte(text), &data); err == nil {
			return data
		}
	}
	return nil
}

// tryExtractJSONFromCodeBlock attempts to parse JSON from a ```json code block.
func tryExtractJSONFromCodeBlock(text string) map[string]interface{} {
	if !strings.Contains(text, "```json") {
		return nil
	}
	start := strings.Index(text, "```json\n")
	if start == -1 {
		return nil
	}
	start += len("```json\n")
	end := strings.Index(text[start:], "\n```")
	if end == -1 {
		return nil
	}
	jsonStr := text[start : start+end]
	var data map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &data); err == nil {
		return data
	}
	return nil
}

// extractMarkdownFromContent collects the first non-JSON text block from content.
func extractMarkdownFromContent(content []interface{}) string {
	for _, item := range content {
		text := extractTextFromContentItem(item)
		if text == "" {
			continue
		}
		if !strings.Contains(text, "```json") {
			return text
		}
	}
	return ""
}

// extractTextFromContentItem extracts the text string from an MCP content item.
func extractTextFromContentItem(item interface{}) string {
	contentItem, ok := item.(map[string]interface{})
	if !ok {
		return ""
	}
	contentType, ok := contentItem["type"].(string)
	if !ok || contentType != "text" {
		return ""
	}
	text, _ := contentItem["text"].(string)
	return text
}
