// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"go.uber.org/zap"
)

// newTestApp creates a minimal application for testing.
func newTestApp() *application {
	return &application{
		logger: zap.NewNop(),
	}
}

func TestEnableCors_AllowedOrigin(t *testing.T) {
	app := newTestApp()

	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	handler := app.EnableCors(inner)

	req := httptest.NewRequest(http.MethodGet, "/api/test", nil)
	req.Header.Set("Origin", "http://localhost:3000")
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}

	acao := rr.Header().Get("Access-Control-Allow-Origin")
	if acao != "http://localhost:3000" {
		t.Errorf("expected Access-Control-Allow-Origin %q, got %q", "http://localhost:3000", acao)
	}

	acac := rr.Header().Get("Access-Control-Allow-Credentials")
	if acac != "true" {
		t.Errorf("expected Access-Control-Allow-Credentials %q, got %q", "true", acac)
	}

	aceh := rr.Header().Get("Access-Control-Expose-Headers")
	if aceh != "X-Session-ID" {
		t.Errorf("expected Access-Control-Expose-Headers %q, got %q", "X-Session-ID", aceh)
	}
}

func TestEnableCors_DisallowedOrigin(t *testing.T) {
	app := newTestApp()

	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	handler := app.EnableCors(inner)

	req := httptest.NewRequest(http.MethodGet, "/api/test", nil)
	req.Header.Set("Origin", "https://random.example.com")
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}

	acao := rr.Header().Get("Access-Control-Allow-Origin")
	if acao != "" {
		t.Errorf("expected no Access-Control-Allow-Origin header, got %q", acao)
	}

	acac := rr.Header().Get("Access-Control-Allow-Credentials")
	if acac != "" {
		t.Errorf("expected no Access-Control-Allow-Credentials header, got %q", acac)
	}
}

func TestRateLimiter_AllowsBelowLimit(t *testing.T) {
	rl := NewRateLimiter(10, time.Second, 5)

	// The first 5 requests (burst size) should all be allowed.
	for i := 0; i < 5; i++ {
		if !rl.Allow("192.0.2.1") {
			t.Fatalf("request %d should have been allowed (burst=%d)", i+1, 5)
		}
	}
}

func TestRateLimiter_BlocksAboveLimit(t *testing.T) {
	rl := NewRateLimiter(10, time.Second, 3)

	// Exhaust all 3 burst tokens.
	for i := 0; i < 3; i++ {
		if !rl.Allow("192.0.2.1") {
			t.Fatalf("request %d should have been allowed", i+1)
		}
	}

	// The next request should be blocked because the burst is exhausted
	// and not enough time has passed for a refill.
	if rl.Allow("192.0.2.1") {
		t.Error("request after burst should have been blocked")
	}
}

func TestGetClientIP_XForwardedFor(t *testing.T) {
	tests := []struct {
		name     string
		xff      string
		expected string
	}{
		{
			name:     "single IP",
			xff:      "203.0.113.50",
			expected: "203.0.113.50",
		},
		{
			name:     "multiple IPs returns first",
			xff:      "203.0.113.50, 70.41.3.18, 150.172.238.178",
			expected: "203.0.113.50",
		},
		{
			name:     "whitespace trimmed",
			xff:      "  203.0.113.50 ",
			expected: "203.0.113.50",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			req.Header.Set("X-Forwarded-For", tc.xff)

			got := getClientIP(req)
			if got != tc.expected {
				t.Errorf("expected %q, got %q", tc.expected, got)
			}
		})
	}
}

func TestGetClientIP_FallbackToRemoteAddr(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	// httptest.NewRequest sets RemoteAddr to "192.0.2.1:1234" by default.
	// Clear proxy headers to force the RemoteAddr fallback path.
	req.Header.Del("X-Forwarded-For")
	req.Header.Del("X-Real-IP")

	got := getClientIP(req)
	if got != "192.0.2.1" {
		t.Errorf("expected %q, got %q", "192.0.2.1", got)
	}
}
