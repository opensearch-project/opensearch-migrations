// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"fmt"
	"strings"
)

// ValidateRequiredParameters checks if all required parameters are present for the workload type
func ValidateRequiredParameters(parsedQuery *ParsedQuery) (bool, []string, string) {
	requirements := GetWorkloadRequirements()
	workloadReq, exists := requirements[parsedQuery.WorkloadType]

	if !exists {
		return true, nil, "" // Unknown workload type, let it through
	}

	missingParams := []string{}

	for _, param := range workloadReq.Parameters {
		if !hasParameter(parsedQuery, param.Name) {
			missingParams = append(missingParams, param.Name)
		}
	}

	if len(missingParams) > 0 {
		followUpQuestion := generateFollowUpQuestion(parsedQuery.WorkloadType, missingParams, workloadReq)
		return false, missingParams, followUpQuestion
	}

	return true, nil, ""
}

// hasParameter checks if a specific parameter is present and valid
func hasParameter(query *ParsedQuery, paramName string) bool {
	switch paramName {
	case "size":
		return query.Size > 0
	case "vectorCount":
		return query.VectorCount > 0
	case "dimensions":
		return query.Dimensions > 0
	case "hotPeriod":
		return query.HotPeriod > 0
	default:
		return false
	}
}

// generateFollowUpQuestion creates a natural language follow-up question for missing parameters
func generateFollowUpQuestion(workloadType string, missingParams []string, requirements WorkloadRequirements) string {
	if len(missingParams) == 0 {
		return ""
	}

	// Build a friendly follow-up question
	var parts []string

	for _, paramName := range missingParams {
		for _, param := range requirements.Parameters {
			if param.Name == paramName {
				parts = append(parts, fmt.Sprintf("**%s**: %s (e.g., %s)",
					param.Name,
					param.Description,
					strings.Join(param.Examples, ", ")))
				break
			}
		}
	}

	workloadTypeName := getWorkloadDisplayName(workloadType)

	var question strings.Builder
	fmt.Fprintf(&question, "I need some additional information to estimate costs for your **%s** workload:\n\n", workloadTypeName)

	for i, part := range parts {
		fmt.Fprintf(&question, "%d. %s\n", i+1, part)
	}

	question.WriteString("\nPlease provide the missing information and I'll calculate the cost estimate for you.")

	return question.String()
}

// getWorkloadDisplayName returns a user-friendly workload type name
func getWorkloadDisplayName(workloadType string) string {
	switch workloadType {
	case "vector":
		return "Vector Search"
	case "search":
		return "Search"
	case "timeseries":
		return "Log Analytics / Time-Series"
	default:
		return workloadType
	}
}
