// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

import (
	"testing"

	"github.com/opensearch-project/opensearch-pricing-calculator/cors"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"go.uber.org/zap"
)

func TestIsOriginAllowedExactMatch(t *testing.T) {
	tests := []struct {
		origin  string
		allowed bool
	}{
		{"http://localhost:3000", true},
		{"http://localhost:5050", true},
		{"http://localhost:8081", true},
	}
	for _, tt := range tests {
		if got := cors.IsOriginAllowed(tt.origin); got != tt.allowed {
			t.Errorf("IsOriginAllowed(%q) = %v, want %v", tt.origin, got, tt.allowed)
		}
	}
}

func TestIsOriginAllowedSuffixMatch(t *testing.T) {
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
		if got := cors.IsOriginAllowed(tt.origin); got != tt.allowed {
			t.Errorf("IsOriginAllowed(%q) = %v, want %v", tt.origin, got, tt.allowed)
		}
	}
}

func TestIsOriginAllowedRejected(t *testing.T) {
	tests := []string{
		"https://evil.com",
		"http://malicious.site",
		"https://not-aws.example.com",
		"",
	}
	for _, origin := range tests {
		if cors.IsOriginAllowed(origin) {
			t.Errorf("IsOriginAllowed(%q) should be false", origin)
		}
	}
}

func TestNewServer(t *testing.T) {
	logger := zap.NewNop()
	ph := provisioned.NewHandler(logger)
	sh := serverless.NewHandler(logger)

	s := NewServer(logger, "http://localhost:8080", ph, sh)
	if s == nil {
		t.Fatal("expected non-nil server")
	}
	if s.baseURL != "http://localhost:8080" {
		t.Errorf("expected baseURL %q, got %q", "http://localhost:8080", s.baseURL)
	}
	if s.logger != logger {
		t.Error("expected logger to be set")
	}
}

func TestNewServerRegisterRoutes(t *testing.T) {
	logger := zap.NewNop()
	ph := provisioned.NewHandler(logger)
	sh := serverless.NewHandler(logger)

	s := NewServer(logger, "http://localhost:8080", ph, sh)
	s.RegisterRoutes()
	// If this doesn't panic, routes were registered successfully
}
