// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"fmt"
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
	// Check if we have structured JSON response (new format)
	if _, hasWorkloadDetails := result["workloadDetails"]; hasWorkloadDetails {
		// Return the entire structured result from MCP
		return result
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
	if recommendations, ok := result["recommendations"]; ok {
		formatted["recommendations"] = recommendations
	}
	if breakdown, ok := result["costBreakdown"]; ok {
		formatted["costBreakdown"] = breakdown
	}
	if config, ok := result["configuration"]; ok {
		formatted["configuration"] = config
	}

	// Extract instance recommendations if present
	if instances, ok := result["instanceRecommendations"]; ok {
		formatted["instanceRecommendations"] = instances
	}

	// Extract top configurations if present (from MCP)
	if topConfigs, ok := result["topConfigurations"]; ok {
		formatted["topConfigurations"] = topConfigs
	}

	// If result has a description field (from MCP), include it
	if description, ok := result["description"]; ok {
		formatted["description"] = description
	}

	return formatted
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

// FormatParsingError creates a response for queries that couldn't be parsed
func (f *ResponseFormatter) FormatParsingError(query string, confidence float64) *EstimateResponse {
	return &EstimateResponse{
		Success: false,
		Error:   "Could not extract sufficient parameters from your query",
		Suggestion: fmt.Sprintf("Please provide more details about your workload. For example: " +
			"'Vector search with 10 million vectors, 768 dimensions in us-east-1' or " +
			"'Time-series workload with 500GB data in us-west-2'"),
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
