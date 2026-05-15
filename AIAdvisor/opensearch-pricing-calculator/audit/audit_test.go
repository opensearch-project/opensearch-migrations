// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package audit

import (
	"context"
	"errors"
	"testing"
	"time"

	"go.uber.org/zap"
)

// --- Context extraction functions ---

func TestGetUserEmail(t *testing.T) {
	ctx := context.WithValue(context.Background(), "userEmail", "user@example.com")
	if got := GetUserEmail(ctx); got != "user@example.com" {
		t.Errorf("expected %q, got %q", "user@example.com", got)
	}
}

func TestGetUserEmail_Empty(t *testing.T) {
	if got := GetUserEmail(context.Background()); got != "" {
		t.Errorf("expected empty, got %q", got)
	}
}

func TestGetUserEmail_Nil(t *testing.T) {
	if got := GetUserEmail(nil); got != "" {
		t.Errorf("expected empty for nil context, got %q", got)
	}
}

func TestGetUserID(t *testing.T) {
	ctx := context.WithValue(context.Background(), "userID", "uid-123")
	if got := GetUserID(ctx); got != "uid-123" {
		t.Errorf("expected %q, got %q", "uid-123", got)
	}
}

func TestGetUserID_Empty(t *testing.T) {
	if got := GetUserID(context.Background()); got != "" {
		t.Errorf("expected empty, got %q", got)
	}
}

func TestGetUserID_Nil(t *testing.T) {
	if got := GetUserID(nil); got != "" {
		t.Errorf("expected empty for nil context, got %q", got)
	}
}

func TestGetUserName(t *testing.T) {
	ctx := context.WithValue(context.Background(), "userName", "John Doe")
	if got := GetUserName(ctx); got != "John Doe" {
		t.Errorf("expected %q, got %q", "John Doe", got)
	}
}

func TestGetUserName_Nil(t *testing.T) {
	if got := GetUserName(nil); got != "" {
		t.Errorf("expected empty for nil context, got %q", got)
	}
}

func TestGetIPAddress(t *testing.T) {
	ctx := context.WithValue(context.Background(), "ipAddress", "192.168.1.1")
	if got := GetIPAddress(ctx); got != "192.168.1.1" {
		t.Errorf("expected %q, got %q", "192.168.1.1", got)
	}
}

func TestGetIPAddress_Nil(t *testing.T) {
	if got := GetIPAddress(nil); got != "" {
		t.Errorf("expected empty for nil context, got %q", got)
	}
}

func TestGetRequestID(t *testing.T) {
	ctx := context.WithValue(context.Background(), "requestID", "req-abc-123")
	if got := GetRequestID(ctx); got != "req-abc-123" {
		t.Errorf("expected %q, got %q", "req-abc-123", got)
	}
}

func TestGetRequestID_Nil(t *testing.T) {
	if got := GetRequestID(nil); got != "" {
		t.Errorf("expected empty for nil context, got %q", got)
	}
}

// --- EventBuilder tests ---

func TestNewEvent(t *testing.T) {
	builder := NewEvent(EventAssistantQuery)
	event := builder.Build()

	if event.EventType != EventAssistantQuery {
		t.Errorf("expected event type %q, got %q", EventAssistantQuery, event.EventType)
	}
	if event.Timestamp.IsZero() {
		t.Error("expected non-zero timestamp")
	}
	if event.Metadata == nil {
		t.Error("expected non-nil metadata")
	}
}

func TestEventBuilder_WithContext(t *testing.T) {
	ctx := context.Background()
	ctx = context.WithValue(ctx, "userEmail", "test@example.com")
	ctx = context.WithValue(ctx, "userID", "uid-1")
	ctx = context.WithValue(ctx, "userName", "Test User")
	ctx = context.WithValue(ctx, "ipAddress", "10.0.0.1")
	ctx = context.WithValue(ctx, "requestID", "req-1")

	event := NewEvent(EventServerlessEstimate).WithContext(ctx).Build()

	if event.UserEmail != "test@example.com" {
		t.Errorf("expected email %q, got %q", "test@example.com", event.UserEmail)
	}
	if event.UserID != "uid-1" {
		t.Errorf("expected userID %q, got %q", "uid-1", event.UserID)
	}
	if event.UserName != "Test User" {
		t.Errorf("expected userName %q, got %q", "Test User", event.UserName)
	}
	if event.IPAddress != "10.0.0.1" {
		t.Errorf("expected IP %q, got %q", "10.0.0.1", event.IPAddress)
	}
	if event.RequestID != "req-1" {
		t.Errorf("expected requestID %q, got %q", "req-1", event.RequestID)
	}
}

func TestEventBuilder_WithUser(t *testing.T) {
	event := NewEvent(EventProvisionedEstimate).
		WithUser("a@b.com", "uid-2", "Alice").
		Build()

	if event.UserEmail != "a@b.com" {
		t.Errorf("expected email %q, got %q", "a@b.com", event.UserEmail)
	}
	if event.UserID != "uid-2" {
		t.Errorf("expected userID %q, got %q", "uid-2", event.UserID)
	}
	if event.UserName != "Alice" {
		t.Errorf("expected userName %q, got %q", "Alice", event.UserName)
	}
}

func TestEventBuilder_WithAction(t *testing.T) {
	event := NewEvent(EventCacheInvalidated).WithAction("invalidate_cache").Build()
	if event.Action != "invalidate_cache" {
		t.Errorf("expected action %q, got %q", "invalidate_cache", event.Action)
	}
}

func TestEventBuilder_WithResource(t *testing.T) {
	event := NewEvent(EventRegionsAccessed).WithResource("/serverless/regions").Build()
	if event.Resource != "/serverless/regions" {
		t.Errorf("expected resource %q, got %q", "/serverless/regions", event.Resource)
	}
}

func TestEventBuilder_WithResult(t *testing.T) {
	event := NewEvent(EventServerlessEstimate).WithResult(ResultSuccess).Build()
	if event.Result != ResultSuccess {
		t.Errorf("expected result %q, got %q", ResultSuccess, event.Result)
	}
}

func TestEventBuilder_WithDuration(t *testing.T) {
	d := 500 * time.Millisecond
	event := NewEvent(EventServerlessEstimate).WithDuration(d).Build()
	if event.Duration != d {
		t.Errorf("expected duration %v, got %v", d, event.Duration)
	}
}

func TestEventBuilder_WithError(t *testing.T) {
	err := errors.New("something went wrong")
	event := NewEvent(EventServerlessEstimate).WithError(err).Build()

	if event.ErrorMsg != "something went wrong" {
		t.Errorf("expected error %q, got %q", "something went wrong", event.ErrorMsg)
	}
	if event.Result != ResultError {
		t.Errorf("expected result %q, got %q", ResultError, event.Result)
	}
}

func TestEventBuilder_WithError_Nil(t *testing.T) {
	event := NewEvent(EventServerlessEstimate).
		WithResult(ResultSuccess).
		WithError(nil).
		Build()

	if event.ErrorMsg != "" {
		t.Errorf("expected empty error, got %q", event.ErrorMsg)
	}
	if event.Result != ResultSuccess {
		t.Errorf("expected result unchanged from Success, got %q", event.Result)
	}
}

func TestEventBuilder_WithMetadata(t *testing.T) {
	event := NewEvent(EventServerlessEstimate).
		WithMetadata("region", "us-east-1").
		WithMetadata("workloadType", "search").
		Build()

	if event.Metadata["region"] != "us-east-1" {
		t.Errorf("expected metadata region %q, got %v", "us-east-1", event.Metadata["region"])
	}
	if event.Metadata["workloadType"] != "search" {
		t.Errorf("expected metadata workloadType %q, got %v", "search", event.Metadata["workloadType"])
	}
}

func TestEventBuilder_Chaining(t *testing.T) {
	event := NewEvent(EventAssistantEstimate).
		WithAction("estimate").
		WithResource("/api/assistant/estimate").
		WithResult(ResultSuccess).
		WithDuration(100 * time.Millisecond).
		WithMetadata("query", "search cluster cost").
		Build()

	if event.EventType != EventAssistantEstimate {
		t.Errorf("unexpected event type: %q", event.EventType)
	}
	if event.Action != "estimate" {
		t.Errorf("unexpected action: %q", event.Action)
	}
	if event.Resource != "/api/assistant/estimate" {
		t.Errorf("unexpected resource: %q", event.Resource)
	}
}

// --- NewLogger and LogEvent tests ---

func TestNewLogger(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	if logger == nil {
		t.Fatal("expected non-nil logger")
	}
}

func TestLogEvent_Success(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
	}
	// Should not panic
	logger.LogEvent(context.Background(), event)
}

func TestLogEvent_Failure(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultFailure,
		ErrorMsg:  "calculation failed",
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEvent_Error(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultError,
		ErrorMsg:  "internal error",
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEvent_WithDuration(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
		Duration:  500 * time.Millisecond,
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEvent_WithMetadata(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
		Metadata:  map[string]interface{}{"region": "us-east-1"},
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEvent_ExtractsContextWhenFieldsEmpty(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	ctx := context.WithValue(context.Background(), "userEmail", "auto@test.com")
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
	}
	// LogEvent should extract userEmail from context when not set on event
	logger.LogEvent(ctx, event)
}

func TestLogEvent_SetsTimestamp(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
	}
	// Zero timestamp should be set by LogEvent
	logger.LogEvent(context.Background(), event)
}

func TestEventBuilder_Log(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	NewEvent(EventServerlessEstimate).
		WithAction("estimate").
		WithResult(ResultSuccess).
		Log(logger)
}
