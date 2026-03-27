// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package scheduler

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"go.uber.org/zap/zaptest"
)

func TestNewCacheScheduler(t *testing.T) {
	logger := zaptest.NewLogger(t)
	baseURL := "http://test.example.com"

	cs := NewCacheScheduler(logger, baseURL)

	if cs == nil {
		t.Fatal("NewCacheScheduler returned nil")
	}

	if cs.baseURL != baseURL {
		t.Errorf("Expected baseURL %s, got %s", baseURL, cs.baseURL)
	}

	if cs.IsRunning() {
		t.Error("Scheduler should not be running initially")
	}
}

func TestSchedulerStartStop(t *testing.T) {
	logger := zaptest.NewLogger(t)
	cs := NewCacheScheduler(logger, "http://test.example.com")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Test start
	err := cs.Start(ctx)
	if err != nil {
		t.Fatalf("Failed to start scheduler: %v", err)
	}

	if !cs.IsRunning() {
		t.Error("Scheduler should be running after start")
	}

	// Test double start should return error
	err = cs.Start(ctx)
	if err == nil {
		t.Error("Expected error when starting already running scheduler")
	}

	// Test stop
	cs.Stop()
	if cs.IsRunning() {
		t.Error("Scheduler should not be running after stop")
	}
}

func TestGetStatus(t *testing.T) {
	logger := zaptest.NewLogger(t)
	cs := NewCacheScheduler(logger, "http://test.example.com")

	status := cs.GetStatus()
	if status.SchedulerStatus != "stopped" {
		t.Errorf("Expected status 'stopped', got '%s'", status.SchedulerStatus)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	_ = cs.Start(ctx)
	defer cs.Stop()

	status = cs.GetStatus()
	if status.SchedulerStatus != "running" {
		t.Errorf("Expected status 'running', got '%s'", status.SchedulerStatus)
	}
}

func TestInvalidateCache(t *testing.T) {
	// Create a mock server
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("Expected POST request, got %s", r.Method)
		}

		if r.URL.Path != "/provisioned/cache/invalidate" {
			t.Errorf("Expected path /provisioned/cache/invalidate, got %s", r.URL.Path)
		}

		err := r.ParseForm()
		if err != nil {
			t.Errorf("Failed to parse form: %v", err)
		}

		if r.Form.Get("update") != "true" {
			t.Errorf("Expected update=true, got update=%s", r.Form.Get("update"))
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"message": "Cache invalidated"}`)
	}))
	defer server.Close()

	logger := zaptest.NewLogger(t)
	cs := NewCacheScheduler(logger, server.URL)

	result := cs.invalidateCache()

	if !result.Success {
		t.Errorf("Expected successful cache invalidation, got error: %s", result.Error)
	}

	if result.StatusCode != http.StatusOK {
		t.Errorf("Expected status code 200, got %d", result.StatusCode)
	}

	if result.Duration == "" {
		t.Error("Expected duration to be set")
	}
}

func TestInvalidateCacheFailure(t *testing.T) {
	// Create a mock server that returns an error
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprint(w, `{"error": "Internal server error"}`)
	}))
	defer server.Close()

	logger := zaptest.NewLogger(t)
	cs := NewCacheScheduler(logger, server.URL)

	result := cs.invalidateCache()

	if result.Success {
		t.Error("Expected failed cache invalidation")
	}

	if result.StatusCode != http.StatusInternalServerError {
		t.Errorf("Expected status code 500, got %d", result.StatusCode)
	}

	if result.Error == "" {
		t.Error("Expected error message to be set")
	}
}

func TestSchedulerStatusLogging(t *testing.T) {
	logger := zaptest.NewLogger(t)
	cs := NewCacheScheduler(logger, "http://test.example.com")

	// Test logSchedulerStatus doesn't panic
	result := CacheInvalidationResult{
		Success:    true,
		Timestamp:  time.Now(),
		Duration:   "100ms",
		Endpoint:   "http://test.example.com/provisioned/cache/invalidate",
		StatusCode: 200,
	}

	// These should not panic
	cs.logSchedulerStatus("started", CacheInvalidationResult{}, "")
	cs.logSchedulerStatus("cache_invalidation_success", result, "")
	cs.logSchedulerStatus("cache_invalidation_failed", result, "test error")
	cs.logSchedulerStatus("stopped", CacheInvalidationResult{}, "")
}

func TestSchedulerStatusJSONSerialization(t *testing.T) {
	now := time.Now()
	result := CacheInvalidationResult{
		Success:    true,
		Timestamp:  now,
		Duration:   "100ms",
		Endpoint:   "http://test.example.com/provisioned/cache/invalidate",
		StatusCode: 200,
	}

	status := Status{
		Timestamp:         now,
		SchedulerStatus:   "cache_invalidation_success",
		LastRun:           now.Add(-24 * time.Hour),
		NextRun:           now.Add(24 * time.Hour),
		CacheInvalidation: result,
	}

	// Test that the status can be marshaled to JSON
	jsonBytes, err := json.Marshal(status)
	if err != nil {
		t.Fatalf("Failed to marshal status to JSON: %v", err)
	}

	// Test that it can be unmarshaled back
	var unmarshaledStatus Status
	err = json.Unmarshal(jsonBytes, &unmarshaledStatus)
	if err != nil {
		t.Fatalf("Failed to unmarshal status from JSON: %v", err)
	}

	if unmarshaledStatus.SchedulerStatus != status.SchedulerStatus {
		t.Errorf("Expected status %s, got %s", status.SchedulerStatus, unmarshaledStatus.SchedulerStatus)
	}
}
