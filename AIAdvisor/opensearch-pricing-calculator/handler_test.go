// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"go.uber.org/zap"
)

func TestErrorResponse_BasicError(t *testing.T) {
	app := &application{logger: zap.NewNop()}

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	rr := httptest.NewRecorder()

	app.errorResponse(rr, req, http.StatusBadRequest, "bad request")

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected status %d, got %d", http.StatusBadRequest, rr.Code)
	}

	if ct := rr.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("expected Content-Type %q, got %q", "application/json", ct)
	}

	var resp ErrorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to unmarshal response: %v", err)
	}
	if resp.Error != "Bad Request" {
		t.Errorf("expected error %q, got %q", "Bad Request", resp.Error)
	}
	if resp.Message != "bad request" {
		t.Errorf("expected message %q, got %q", "bad request", resp.Message)
	}
}

func TestErrorResponseWithCode(t *testing.T) {
	app := &application{logger: zap.NewNop()}

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	rr := httptest.NewRecorder()

	app.errorResponseWithCode(rr, req, http.StatusUnauthorized, "not authenticated", "UNAUTHORIZED")

	if rr.Code != http.StatusUnauthorized {
		t.Errorf("expected status %d, got %d", http.StatusUnauthorized, rr.Code)
	}

	var resp ErrorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to unmarshal response: %v", err)
	}
	if resp.Code != "UNAUTHORIZED" {
		t.Errorf("expected code %q, got %q", "UNAUTHORIZED", resp.Code)
	}
	if resp.Message != "not authenticated" {
		t.Errorf("expected message %q, got %q", "not authenticated", resp.Message)
	}
}

func TestValidationErrorResponse(t *testing.T) {
	app := &application{logger: zap.NewNop()}

	req := httptest.NewRequest(http.MethodPost, "/test", nil)
	rr := httptest.NewRecorder()

	fields := map[string]string{
		"email": "required",
		"name":  "too short",
	}
	app.validationErrorResponse(rr, req, "validation failed", fields)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected status %d, got %d", http.StatusBadRequest, rr.Code)
	}

	var resp ErrorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to unmarshal response: %v", err)
	}
	if resp.Code != "VALIDATION_ERROR" {
		t.Errorf("expected code %q, got %q", "VALIDATION_ERROR", resp.Code)
	}
	if resp.Fields["email"] != "required" {
		t.Errorf("expected fields[email] %q, got %q", "required", resp.Fields["email"])
	}
	if resp.Fields["name"] != "too short" {
		t.Errorf("expected fields[name] %q, got %q", "too short", resp.Fields["name"])
	}
}

// --- writeJSON tests ---

func TestWriteJSON_Success(t *testing.T) {
	app := newTestApp()
	rr := httptest.NewRecorder()
	data := map[string]string{"hello": "world"}
	app.writeJSON(rr, http.StatusOK, data)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}
	if ct := rr.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("expected Content-Type %q, got %q", "application/json", ct)
	}
	var result map[string]string
	if err := json.Unmarshal(rr.Body.Bytes(), &result); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if result["hello"] != "world" {
		t.Errorf("expected hello=world, got %q", result["hello"])
	}
}

func TestWriteJSON_UnmarshalableData(t *testing.T) {
	app := newTestApp()
	rr := httptest.NewRecorder()
	// Functions cannot be marshaled to JSON
	app.writeJSON(rr, http.StatusOK, func() {})

	if rr.Code != http.StatusInternalServerError {
		t.Errorf("expected status %d, got %d", http.StatusInternalServerError, rr.Code)
	}
}

// --- Home handler tests ---

func TestHome_ReturnsStatus(t *testing.T) {
	app := newTestApp()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rr := httptest.NewRecorder()

	app.Home(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}

	var payload struct {
		Status  string `json:"status"`
		Message string `json:"message"`
		Version string `json:"version"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &payload); err != nil {
		t.Fatalf("failed to unmarshal response: %v", err)
	}
	if payload.Status != "active" {
		t.Errorf("expected status %q, got %q", "active", payload.Status)
	}
	if payload.Version != ServiceVersion {
		t.Errorf("expected version %q, got %q", ServiceVersion, payload.Version)
	}
}

// --- Health handler tests ---

func TestHealth_ReturnsHealthy(t *testing.T) {
	app := newTestApp()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()

	app.Health(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}
	body := rr.Body.String()
	if !strings.Contains(body, `"status":"healthy"`) {
		t.Errorf("expected healthy status in body, got %q", body)
	}
}

// --- ServerlessEstimateV2 handler error path tests ---

func TestServerlessEstimateV2_EmptyBody(t *testing.T) {
	app := newTestApp()
	req := httptest.NewRequest(http.MethodPost, "/serverless/v2/estimate", nil)
	rr := httptest.NewRecorder()

	app.ServerlessEstimateV2(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected status %d, got %d", http.StatusBadRequest, rr.Code)
	}
	var resp ErrorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if resp.Message != "invalid JSON request body" {
		t.Errorf("expected message %q, got %q", "invalid JSON request body", resp.Message)
	}
}

func TestServerlessEstimateV2_InvalidJSON(t *testing.T) {
	app := newTestApp()
	body := strings.NewReader(`{invalid json}`)
	req := httptest.NewRequest(http.MethodPost, "/serverless/v2/estimate", body)
	rr := httptest.NewRecorder()

	app.ServerlessEstimateV2(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected status %d, got %d", http.StatusBadRequest, rr.Code)
	}
	var resp ErrorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if resp.Message != "invalid JSON request body" {
		t.Errorf("expected message %q, got %q", "invalid JSON request body", resp.Message)
	}
}

func TestServerlessEstimateV2_ValidSearchRequest(t *testing.T) {
	app := newTestApp()
	body := strings.NewReader(`{
		"region": "US East (N. Virginia)",
		"edp": 0,
		"ingest": {"minIndexingRate": 1, "maxIndexingRate": 5, "timePerDayAtMax": 4},
		"search": {"collectionSize": 50, "minQueryRate": 10, "maxQueryRate": 100, "hoursAtMaxRate": 8}
	}`)
	req := httptest.NewRequest(http.MethodPost, "/serverless/v2/estimate", body)
	rr := httptest.NewRecorder()

	app.ServerlessEstimateV2(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
		t.Logf("body: %s", rr.Body.String())
	}
	if ct := rr.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("expected Content-Type %q, got %q", "application/json", ct)
	}
}

func TestServerlessEstimateV2_ValidTimeSeriesRequest(t *testing.T) {
	app := newTestApp()
	body := strings.NewReader(`{
		"region": "US East (N. Virginia)",
		"ingest": {"minIndexingRate": 1, "maxIndexingRate": 5},
		"timeSeries": {"dailyIndexSize": 10, "daysInHot": 7, "daysInWarm": 30}
	}`)
	req := httptest.NewRequest(http.MethodPost, "/serverless/v2/estimate", body)
	rr := httptest.NewRecorder()

	app.ServerlessEstimateV2(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
		t.Logf("body: %s", rr.Body.String())
	}
}

func TestServerlessEstimateV2_ValidVectorRequest(t *testing.T) {
	app := newTestApp()
	body := strings.NewReader(`{
		"region": "US East (N. Virginia)",
		"ingest": {"minIndexingRate": 1, "maxIndexingRate": 5},
		"vector": {"vectorEngineType": "hnsw", "dimensionsCount": 768, "vectorCount": 10000, "maxEdges": 16}
	}`)
	req := httptest.NewRequest(http.MethodPost, "/serverless/v2/estimate", body)
	rr := httptest.NewRecorder()

	app.ServerlessEstimateV2(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
		t.Logf("body: %s", rr.Body.String())
	}
}

func TestServerlessEstimateV2_ReadBodyError(t *testing.T) {
	app := newTestApp()
	// Create a request with a body that fails on read
	req := httptest.NewRequest(http.MethodPost, "/serverless/v2/estimate", &errorReader{})
	rr := httptest.NewRecorder()

	app.ServerlessEstimateV2(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected status %d, got %d", http.StatusBadRequest, rr.Code)
	}
	var resp ErrorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if resp.Message != "failed to read request body" {
		t.Errorf("expected message %q, got %q", "failed to read request body", resp.Message)
	}
}

// --- ServerlessRegions handler tests ---

func TestServerlessRegions_ReturnsJSON(t *testing.T) {
	app := newTestApp()
	req := httptest.NewRequest(http.MethodGet, "/serverless/regions", nil)
	rr := httptest.NewRecorder()

	app.ServerlessRegions(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}
	if ct := rr.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("expected Content-Type %q, got %q", "application/json", ct)
	}
	// Should contain a "regions" key
	var result map[string]interface{}
	if err := json.Unmarshal(rr.Body.Bytes(), &result); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if _, exists := result["regions"]; !exists {
		t.Error("expected 'regions' key in response")
	}
}

// --- ServerlessPrice handler tests ---

func TestServerlessPrice_ReturnsJSON(t *testing.T) {
	app := newTestApp()
	req := httptest.NewRequest(http.MethodGet, "/serverless/price", nil)
	rr := httptest.NewRecorder()

	app.ServerlessPrice(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected status %d, got %d", http.StatusOK, rr.Code)
	}
	if ct := rr.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("expected Content-Type %q, got %q", "application/json", ct)
	}
}

// --- contains helper tests ---

func TestContains(t *testing.T) {
	slice := []string{"a", "b", "c"}
	if !contains(slice, "a") {
		t.Error("expected contains to return true for 'a'")
	}
	if !contains(slice, "c") {
		t.Error("expected contains to return true for 'c'")
	}
	if contains(slice, "d") {
		t.Error("expected contains to return false for 'd'")
	}
	if contains(nil, "a") {
		t.Error("expected contains to return false for nil slice")
	}
}

// errorReader implements io.Reader that always returns an error
type errorReader struct{}

func (e *errorReader) Read(p []byte) (n int, err error) {
	return 0, io.ErrUnexpectedEOF
}

func TestErrorResponse_OmitsEmptyFields(t *testing.T) {
	app := &application{logger: zap.NewNop()}

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	rr := httptest.NewRecorder()

	app.errorResponse(rr, req, http.StatusNotFound, "not found")

	// Unmarshal into a raw map to check which fields are present
	var raw map[string]interface{}
	if err := json.Unmarshal(rr.Body.Bytes(), &raw); err != nil {
		t.Fatalf("failed to unmarshal response: %v", err)
	}

	if _, exists := raw["code"]; exists {
		t.Error("expected 'code' to be omitted from JSON when empty")
	}
	if _, exists := raw["fields"]; exists {
		t.Error("expected 'fields' to be omitted from JSON when empty")
	}

	// Verify required fields are present
	if _, exists := raw["error"]; !exists {
		t.Error("expected 'error' field to be present")
	}
	if _, exists := raw["message"]; !exists {
		t.Error("expected 'message' field to be present")
	}
}
