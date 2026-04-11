// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"testing"
)

func TestDetectStandby(t *testing.T) {
	parser := NewNLPParser()

	tests := []struct {
		name     string
		query    string
		expected bool
	}{
		{
			name:     "with standby",
			query:    "Vector search with 10M vectors with standby",
			expected: true,
		},
		{
			name:     "standby az",
			query:    "Search workload 500GB standby az",
			expected: true,
		},
		{
			name:     "multi-az with standby",
			query:    "Time-series 100GB daily multi-az with standby",
			expected: true,
		},
		{
			name:     "multi az with standby (no hyphen)",
			query:    "Vector search multi az with standby",
			expected: true,
		},
		{
			name:     "99.99% availability",
			query:    "Search workload with 99.99% availability",
			expected: true,
		},
		{
			name:     "99.99 percent (with space)",
			query:    "Vector search 99.99 % uptime",
			expected: true,
		},
		{
			name:     "highest availability",
			query:    "Production cluster with highest availability",
			expected: true,
		},
		{
			name:     "maximum availability",
			query:    "Search workload maximum availability",
			expected: true,
		},
		{
			name:     "four nines",
			query:    "Vector search with four nines availability",
			expected: true,
		},
		{
			name:     "no standby mentioned",
			query:    "Vector search with 10M vectors 768 dimensions",
			expected: false,
		},
		{
			name:     "just production",
			query:    "Production search cluster 500GB",
			expected: false,
		},
		{
			name:     "high availability (not highest)",
			query:    "High availability cluster",
			expected: false,
		},
		{
			name:     "99.9% (three nines)",
			query:    "99.9% uptime requirement",
			expected: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, _ := parser.Parse(tt.query)
			if result.MultiAzWithStandby != tt.expected {
				t.Errorf("detectStandby(%q) = %v, want %v", tt.query, result.MultiAzWithStandby, tt.expected)
			}
		})
	}
}

func TestParseMultiAzWithStandby(t *testing.T) {
	parser := NewNLPParser()

	// Test that standby sets appropriate defaults when combined with other params
	query := "Vector search with 10M vectors 768 dimensions with standby in us-east-1"
	result, err := parser.Parse(query)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	if !result.MultiAzWithStandby {
		t.Error("Expected MultiAzWithStandby to be true")
	}
	if result.WorkloadType != "vector" {
		t.Errorf("Expected workload type 'vector', got %q", result.WorkloadType)
	}
	if result.VectorCount != 10000000 {
		t.Errorf("Expected vector count 10000000, got %d", result.VectorCount)
	}
	if result.Dimensions != 768 {
		t.Errorf("Expected dimensions 768, got %d", result.Dimensions)
	}
	if result.Region != "us-east-1" {
		t.Errorf("Expected region 'us-east-1', got %q", result.Region)
	}
}
