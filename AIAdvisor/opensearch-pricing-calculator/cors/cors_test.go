// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cors

import (
	"testing"
)

func TestIsOriginAllowed_ExactMatch(t *testing.T) {
	tests := []struct {
		origin  string
		allowed bool
	}{
		{"http://localhost:3000", true},
		{"http://localhost:3002", true},
		{"http://localhost:5050", true},
		{"http://localhost:8081", true},
	}
	for _, tt := range tests {
		if got := IsOriginAllowed(tt.origin); got != tt.allowed {
			t.Errorf("IsOriginAllowed(%q) = %v, want %v", tt.origin, got, tt.allowed)
		}
	}
}

func TestIsOriginAllowed_SuffixMatch(t *testing.T) {
	tests := []struct {
		origin  string
		allowed bool
	}{
		{"https://calculator.aws.dev", true},
		{"https://my-app.example.aws.dev", true},
		{"https://some-service.amazonaws.com", true},
		// HTTP not allowed for production domains
		{"http://calculator.aws.dev", false},
		{"http://some-service.amazonaws.com", false},
	}
	for _, tt := range tests {
		if got := IsOriginAllowed(tt.origin); got != tt.allowed {
			t.Errorf("IsOriginAllowed(%q) = %v, want %v", tt.origin, got, tt.allowed)
		}
	}
}

func TestIsOriginAllowed_Rejected(t *testing.T) {
	tests := []string{
		"https://evil.com",
		"http://malicious.site",
		"https://not-aws.example.com",
		"",
	}
	for _, origin := range tests {
		if IsOriginAllowed(origin) {
			t.Errorf("IsOriginAllowed(%q) should be false", origin)
		}
	}
}

func TestAllowedOrigins_ContainsDefaults(t *testing.T) {
	defaults := []string{
		"http://localhost:3000",
		"http://localhost:3002",
		"http://localhost:5050",
		"http://localhost:8081",
	}
	for _, origin := range defaults {
		if !AllowedOrigins[origin] {
			t.Errorf("AllowedOrigins should contain %q", origin)
		}
	}
}

func TestAllowedOriginSuffixes(t *testing.T) {
	if len(AllowedOriginSuffixes) != 2 {
		t.Errorf("expected 2 suffixes, got %d", len(AllowedOriginSuffixes))
	}
}
