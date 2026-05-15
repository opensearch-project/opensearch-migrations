// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"fmt"
	"strings"
)

// ResponseFormatter formats MCP results into frontend-friendly responses
type ResponseFormatter struct{}

// NewResponseFormatter creates a new response formatter
func NewResponseFormatter() *ResponseFormatter {
	return &ResponseFormatter{}
}

// Format combines MCP results with metadata into final response
func (f *ResponseFormatter) Format(
	parsedQuery *ParsedQuery,
	managedResult map[string]interface{},
	serverlessResult map[string]interface{},
	metadata *Metadata,
) *EstimateResponse {
	response := &EstimateResponse{
		Success:              true,
		WorkloadType:         parsedQuery.WorkloadType,
		DeploymentPreference: parsedQuery.DeploymentPreference,
		ParsedQuery:          parsedQuery,
		Metadata:             metadata,
	}

	// Add managed estimate if available
	if managedResult != nil {
		response.Managed = f.formatManagedResult(managedResult)
	}

	// Add serverless estimate if available
	if serverlessResult != nil {
		response.Serverless = f.formatServerlessResult(serverlessResult)
	}

	// Add suggestion based on results
	if response.Managed != nil && response.Serverless != nil {
		response.Suggestion = f.generateSuggestion(response.Managed, response.Serverless, parsedQuery)
	}

	// Generate contextual assistant message with actual costs
	response.AssistantMessage = f.generateAssistantMessage(parsedQuery, response.Managed, response.Serverless)

	// Add serverless unavailability note when managed succeeded but serverless is nil
	if response.Managed != nil && response.Serverless == nil {
		response.ServerlessNote = "Serverless is not available for this configuration."
	}

	return response
}

// FormatError creates an error response
func (f *ResponseFormatter) FormatError(err error, suggestion string) *EstimateResponse {
	return &EstimateResponse{
		Success:    false,
		Error:      err.Error(),
		Suggestion: suggestion,
	}
}

func (f *ResponseFormatter) formatManagedResult(result map[string]interface{}) map[string]interface{} {
	// Check if we have structured JSON response (already in frontend format)
	if _, hasWorkloadDetails := result["workloadDetails"]; hasWorkloadDetails {
		return result
	}

	// Raw provisioned.EstimateResponse from ToolExecutor (post-MCP refactoring)
	// Has "clusterConfigs" at the top level — transform into the frontend's expected shape.
	if clusterConfigs, hasCC := result["clusterConfigs"]; hasCC {
		return f.transformProvisionedResponse(result, clusterConfigs)
	}

	// Fallback: pass through whatever we have
	return result
}

// transformProvisionedResponse converts a raw provisioned.EstimateResponse map
// into the structured format the frontend's ManagedResults.jsx expects:
//
//	{workloadDetails, topClusterConfigurations, summary, originalRequest}
func (f *ResponseFormatter) transformProvisionedResponse(result map[string]interface{}, clusterConfigs interface{}) map[string]interface{} {
	formatted := make(map[string]interface{})

	// --- workloadDetails ---
	wd := make(map[string]interface{})
	if v, ok := result["totalHotShards"]; ok {
		wd["activePrimaryShards"] = v
	}
	if v, ok := result["totalActiveShards"]; ok {
		wd["totalActiveShards"] = v
	}
	if v, ok := result["totalHotStorage"]; ok {
		wd["totalHotStorage"] = v
	}
	if v, ok := result["totalWarmStorage"]; ok {
		wd["totalWarmStorage"] = v
	}
	if v, ok := result["totalColdStorage"]; ok {
		wd["totalColdStorage"] = v
	}
	if v, ok := result["totalMemoryRequiredForVectors"]; ok {
		wd["totalMemoryRequiredForVectors"] = v
	}
	formatted["workloadDetails"] = wd

	// --- summary.workloadType ---
	workloadType := "search"
	if _, ok := result["vectorRequest"]; ok {
		workloadType = "vector"
	} else if _, ok := result["timeSeriesRequest"]; ok {
		workloadType = "timeSeries"
	}
	formatted["summary"] = map[string]interface{}{"workloadType": workloadType}

	// --- originalRequest (for What-If analysis) ---
	originalRequest := make(map[string]interface{})
	if v, ok := result["searchRequest"]; ok {
		originalRequest["searchRequest"] = v
	}
	if v, ok := result["timeSeriesRequest"]; ok {
		originalRequest["timeSeriesRequest"] = v
	}
	if v, ok := result["vectorRequest"]; ok {
		originalRequest["vectorRequest"] = v
	}
	formatted["originalRequest"] = originalRequest

	// --- topClusterConfigurations ---
	configs, ok := clusterConfigs.([]interface{})
	if !ok {
		formatted["topClusterConfigurations"] = []interface{}{}
		return formatted
	}

	topConfigs := make([]interface{}, 0, len(configs))
	for i, cfg := range configs {
		cfgMap, ok := cfg.(map[string]interface{})
		if !ok {
			continue
		}
		topConfigs = append(topConfigs, transformClusterConfig(cfgMap, i+1))
	}
	formatted["topClusterConfigurations"] = topConfigs

	// --- totalMonthlyCost (for extractCost / conversation summary) ---
	if len(configs) > 0 {
		if first, ok := configs[0].(map[string]interface{}); ok {
			if tc, ok := first["totalCost"].(float64); ok {
				formatted["totalMonthlyCost"] = tc
			}
		}
	}

	return formatted
}

// transformClusterConfig maps a raw ClusterConfig map into the shape
// the frontend's ConfigurationCard component expects.
func transformClusterConfig(config map[string]interface{}, rank int) map[string]interface{} {
	out := map[string]interface{}{
		"rank": rank,
	}

	// --- architecture ---
	arch := make(map[string]interface{})
	if hot, ok := config["hotNodes"].(map[string]interface{}); ok {
		arch["hotNodes"] = transformNodeFields(hot)
	}
	if leader, ok := config["leaderNodes"].(map[string]interface{}); ok {
		arch["leaderNodes"] = transformNodeFields(leader)
	}
	if warm, ok := config["warmNodes"].(map[string]interface{}); ok {
		arch["warmNodes"] = transformNodeFields(warm)
	}
	if cold, ok := config["coldStorage"].(map[string]interface{}); ok {
		arch["coldStorage"] = cold
	}
	out["architecture"] = arch

	// --- costs ---
	costs := make(map[string]interface{})
	if tc, ok := config["totalCost"].(float64); ok {
		costs["totalMonthlyCost"] = tc
	}
	out["costs"] = costs

	// --- description ---
	out["description"] = generateConfigDescription(config, rank)

	return out
}

// transformNodeFields renames backend JSON keys to the names the frontend expects:
//
//	type → instanceType, JVMMemory → jvmMemory,
//	maxNumberOfSearchThreads → maxSearchThreads,
//	maxNumberOfWriteThreads  → maxIndexThreads.
func transformNodeFields(node map[string]interface{}) map[string]interface{} {
	out := make(map[string]interface{}, len(node))
	for k, v := range node {
		switch k {
		case "type":
			out["instanceType"] = v
		case "JVMMemory":
			out["jvmMemory"] = v
		case "maxNumberOfSearchThreads":
			out["maxSearchThreads"] = v
		case "maxNumberOfWriteThreads":
			out["maxIndexThreads"] = v
		case "JVMMemoryPerNode":
			out["jvmMemoryPerNode"] = v
		default:
			out[k] = v
		}
	}
	return out
}

// generateConfigDescription creates a human-readable label for a cluster config.
func generateConfigDescription(config map[string]interface{}, rank int) string {
	hotNodes, _ := config["hotNodes"].(map[string]interface{})
	if hotNodes == nil {
		return fmt.Sprintf("Configuration %d", rank)
	}
	instanceType, _ := hotNodes["type"].(string)
	family, _ := hotNodes["family"].(string)
	count, _ := hotNodes["count"].(float64) // JSON numbers are float64

	if instanceType != "" && count > 0 {
		if family != "" {
			return fmt.Sprintf("%s — %dx %s", family, int(count), instanceType)
		}
		return fmt.Sprintf("%dx %s", int(count), instanceType)
	}
	return fmt.Sprintf("Configuration %d", rank)
}

func (f *ResponseFormatter) formatServerlessResult(result map[string]interface{}) map[string]interface{} {
	// Check if we have structured JSON response (new format with region/price fields)
	if _, hasRegion := result["region"]; hasRegion {
		if _, hasPrice := result["price"]; hasPrice {
			// Return the entire structured result from MCP
			return result
		}
	}

	// Legacy format: extract specific fields
	formatted := make(map[string]interface{})

	// Extract common fields
	if total, ok := result["totalMonthlyCost"]; ok {
		formatted["totalMonthlyCost"] = total
	}
	if estimate, ok := result["estimate"]; ok {
		formatted["estimate"] = estimate
	}
	if breakdown, ok := result["costBreakdown"]; ok {
		formatted["costBreakdown"] = breakdown
	}
	if ocuBreakdown, ok := result["ocuBreakdown"]; ok {
		formatted["ocuBreakdown"] = ocuBreakdown
	}

	// Extract configuration details
	if config, ok := result["configuration"]; ok {
		formatted["configuration"] = config
	}

	// If result has a description field (from MCP), include it
	if description, ok := result["description"]; ok {
		formatted["description"] = description
	}

	return formatted
}

func (f *ResponseFormatter) generateSuggestion(
	managedResult map[string]interface{},
	serverlessResult map[string]interface{},
	parsedQuery *ParsedQuery,
) string {
	// Extract costs for comparison
	managedCost := f.extractCost(managedResult)
	serverlessCost := f.extractCost(serverlessResult)

	if managedCost == 0 && serverlessCost == 0 {
		return "Review the detailed estimates above to choose the best deployment option for your needs."
	}

	// Calculate difference
	var suggestion string
	if managedCost > 0 && serverlessCost > 0 {
		diff := ((managedCost - serverlessCost) / serverlessCost) * 100

		if diff > 20 {
			suggestion = fmt.Sprintf("Serverless is approximately %.0f%% cheaper for this workload. Consider serverless if you have variable or unpredictable load patterns.", diff)
		} else if diff < -20 {
			suggestion = fmt.Sprintf("Managed (provisioned) is approximately %.0f%% cheaper for this workload. Consider managed if you have consistent, predictable load patterns.", -diff)
		} else {
			suggestion = "Both deployment options have similar costs. Choose based on operational preferences: serverless for hands-off management, or managed for more control."
		}
	} else if managedCost > 0 {
		suggestion = "Only managed (provisioned) estimate is available. This option provides more control and predictable pricing."
	} else if serverlessCost > 0 {
		suggestion = "Only serverless estimate is available. This option provides automatic scaling and pay-per-use pricing."
	}

	// Add workload-specific recommendations
	switch parsedQuery.WorkloadType {
	case "vector":
		suggestion += " For vector search workloads, consider the trade-off between performance (managed with specific instance types) and operational simplicity (serverless)."
	case "timeseries":
		suggestion += " For time-series data, managed clusters with UltraWarm can provide significant cost savings for historical data."
	case "search":
		suggestion += " For search workloads, consider your query patterns and data freshness requirements when choosing between managed and serverless."
	}

	return suggestion
}

func (f *ResponseFormatter) extractCost(result map[string]interface{}) float64 {
	// Try to extract total monthly cost
	if total, ok := result["totalMonthlyCost"].(float64); ok {
		return total
	}

	// Try from estimate object
	if estimate, ok := result["estimate"].(map[string]interface{}); ok {
		if total, ok := estimate["totalMonthlyCost"].(float64); ok {
			return total
		}
	}

	return 0
}

// generateAssistantMessage creates a deterministic summary string with actual costs
// from the calculation results. Used as the assistant's conversational response text.
func (f *ResponseFormatter) generateAssistantMessage(
	parsedQuery *ParsedQuery,
	managedResult map[string]interface{},
	serverlessResult map[string]interface{},
) string {
	workloadLabel := formatWorkloadType(parsedQuery.WorkloadType)
	region := parsedQuery.Region
	if region == "" {
		region = "US East (N. Virginia)"
	}

	paramSummary := buildParamSummaryForMessage(parsedQuery)

	lowestManaged, highestManaged := extractManagedCostRange(managedResult)
	serverlessCost := extractServerlessCost(serverlessResult)

	hasManaged := lowestManaged > 0
	hasServerless := serverlessCost > 0

	switch {
	case hasManaged && hasServerless:
		return fmt.Sprintf(
			"For your %s workload (%s) in %s: 3 managed options from %s/mo to %s/mo, plus a serverless alternative at %s/mo.",
			workloadLabel, paramSummary, region,
			formatDollar(lowestManaged), formatDollar(highestManaged), formatDollar(serverlessCost))
	case hasManaged:
		return fmt.Sprintf(
			"For your %s workload (%s) in %s: 3 managed options from %s/mo to %s/mo.",
			workloadLabel, paramSummary, region,
			formatDollar(lowestManaged), formatDollar(highestManaged))
	case hasServerless:
		return fmt.Sprintf(
			"For your %s workload (%s) in %s: serverless estimate at %s/mo.",
			workloadLabel, paramSummary, region, formatDollar(serverlessCost))
	default:
		return "Sorry, I was unable to generate cost estimates for this configuration. Please try adjusting your parameters."
	}
}

// formatWorkloadType converts internal workload type strings to display labels.
func formatWorkloadType(wt string) string {
	switch wt {
	case "vector":
		return "vector search"
	case "timeseries", "timeSeries":
		return "time-series"
	case "search":
		return "search"
	default:
		return wt
	}
}

// buildParamSummaryForMessage creates a brief parameter summary for the assistant message.
func buildParamSummaryForMessage(pq *ParsedQuery) string {
	var parts []string
	if pq.Size > 0 {
		if pq.WorkloadType == "timeseries" || pq.WorkloadType == "timeSeries" {
			parts = append(parts, fmt.Sprintf("%d GB/day", pq.Size))
		} else {
			parts = append(parts, fmt.Sprintf("%d GB", pq.Size))
		}
	}
	if pq.VectorCount > 0 {
		parts = append(parts, fmt.Sprintf("%dM vectors", pq.VectorCount/1000000))
	}
	if pq.Dimensions > 0 {
		parts = append(parts, fmt.Sprintf("%dd", pq.Dimensions))
	}
	if pq.VectorEngine != "" {
		parts = append(parts, strings.ToUpper(pq.VectorEngine))
	}
	if pq.HotPeriod > 0 {
		parts = append(parts, fmt.Sprintf("%dd hot", pq.HotPeriod))
	}
	if pq.WarmPeriod > 0 {
		parts = append(parts, fmt.Sprintf("%dd warm", pq.WarmPeriod))
	}
	if len(parts) == 0 {
		return "default parameters"
	}
	return strings.Join(parts, ", ")
}

// extractManagedCostRange extracts the lowest and highest monthly cost from managed configs.
func extractManagedCostRange(managed map[string]interface{}) (lowest, highest float64) {
	if managed == nil {
		return 0, 0
	}
	configs, ok := managed["topClusterConfigurations"].([]interface{})
	if !ok || len(configs) == 0 {
		if tc, ok := managed["totalMonthlyCost"].(float64); ok {
			return tc, tc
		}
		return 0, 0
	}
	lowest = -1
	for _, cfg := range configs {
		cfgMap, ok := cfg.(map[string]interface{})
		if !ok {
			continue
		}
		costs, ok := cfgMap["costs"].(map[string]interface{})
		if !ok {
			continue
		}
		tc, ok := costs["totalMonthlyCost"].(float64)
		if !ok {
			continue
		}
		if lowest < 0 || tc < lowest {
			lowest = tc
		}
		if tc > highest {
			highest = tc
		}
	}
	if lowest < 0 {
		lowest = 0
	}
	return lowest, highest
}

// extractServerlessCost extracts the monthly cost from a serverless result.
func extractServerlessCost(serverless map[string]interface{}) float64 {
	if serverless == nil {
		return 0
	}
	priceMap, ok := serverless["price"].(map[string]interface{})
	if !ok {
		return 0
	}
	monthMap, ok := priceMap["month"].(map[string]interface{})
	if !ok {
		return 0
	}
	if total, ok := monthMap["total"].(float64); ok {
		return total
	}
	return 0
}

// formatDollar formats a float as a dollar amount with comma separators.
func formatDollar(amount float64) string {
	s := fmt.Sprintf("%.2f", amount)
	parts := strings.SplitN(s, ".", 2)
	intPart := parts[0]
	decPart := parts[1]

	if len(intPart) > 3 {
		var result []byte
		for i, ch := range intPart {
			if i > 0 && (len(intPart)-i)%3 == 0 {
				result = append(result, ',')
			}
			result = append(result, byte(ch))
		}
		intPart = string(result)
	}
	return "$" + intPart + "." + decPart
}

// FormatParsingError creates a response for queries that couldn't be parsed
func (f *ResponseFormatter) FormatParsingError(query string, confidence float64) *EstimateResponse {
	return &EstimateResponse{
		Success: false,
		Error:   "Could not extract sufficient parameters from your query",
		Suggestion: fmt.Sprintf("Please provide more details about your workload. For example: " +
			"'Vector search with 10 million vectors, 768 dimensions in us-east-1' or " +
			"'Time-series workload with 500GB data in us-west-2' or " +
			"'Search workload with 100GB in us-isob-east-1' (Secret region)"),
		Metadata: &Metadata{
			Confidence: confidence,
		},
	}
}

// FormatCachedResponse adds caching metadata to a response
func (f *ResponseFormatter) FormatCachedResponse(response *EstimateResponse) *EstimateResponse {
	if response.Metadata == nil {
		response.Metadata = &Metadata{}
	}
	response.Metadata.CachedResponse = true
	return response
}
