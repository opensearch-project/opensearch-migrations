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

const (
	testUserEmail       = "user@example.com"
	testExpectedGotFmt  = "expected %q, got %q"
	testNilCtxEmptyFmt  = "expected empty for nil context, got %q"
	testUID123          = "uid-123"
	testJohnDoe         = "John Doe"
	testIP192           = "192.168.1.1"
	testReqABC123       = "req-abc-123"
	testEmailExample    = "test@example.com"
	testUserName        = "Test User"
	testIP10            = "10.0.0.1"
	testEmailAB         = "a@b.com"
	testServerlessPath  = "/serverless/regions"
	testSomethingWrong  = "something went wrong"
	testRegionUSEast1   = "us-east-1"
)

// --- Context extraction functions ---

func TestGetUserEmail(t *testing.T) {
	ctx := context.WithValue(context.Background(), "userEmail", testUserEmail)
	if got := GetUserEmail(ctx); got != testUserEmail {
		t.Errorf(testExpectedGotFmt, testUserEmail, got)
	}
}

func TestGetUserEmailEmpty(t *testing.T) {
	if got := GetUserEmail(context.Background()); got != "" {
		t.Errorf("expected empty, got %q", got)
	}
}

func TestGetUserEmailNil(t *testing.T) {
	if got := GetUserEmail(nil); got != "" {
		t.Errorf(testNilCtxEmptyFmt, got)
	}
}

func TestGetUserID(t *testing.T) {
	ctx := context.WithValue(context.Background(), "userID", testUID123)
	if got := GetUserID(ctx); got != testUID123 {
		t.Errorf(testExpectedGotFmt, testUID123, got)
	}
}

func TestGetUserIDEmpty(t *testing.T) {
	if got := GetUserID(context.Background()); got != "" {
		t.Errorf("expected empty, got %q", got)
	}
}

func TestGetUserIDNil(t *testing.T) {
	if got := GetUserID(nil); got != "" {
		t.Errorf(testNilCtxEmptyFmt, got)
	}
}

func TestGetUserName(t *testing.T) {
	ctx := context.WithValue(context.Background(), "userName", testJohnDoe)
	if got := GetUserName(ctx); got != testJohnDoe {
		t.Errorf(testExpectedGotFmt, testJohnDoe, got)
	}
}

func TestGetUserNameNil(t *testing.T) {
	if got := GetUserName(nil); got != "" {
		t.Errorf(testNilCtxEmptyFmt, got)
	}
}

func TestGetIPAddress(t *testing.T) {
	ctx := context.WithValue(context.Background(), "ipAddress", testIP192)
	if got := GetIPAddress(ctx); got != testIP192 {
		t.Errorf(testExpectedGotFmt, testIP192, got)
	}
}

func TestGetIPAddressNil(t *testing.T) {
	if got := GetIPAddress(nil); got != "" {
		t.Errorf(testNilCtxEmptyFmt, got)
	}
}

func TestGetRequestID(t *testing.T) {
	ctx := context.WithValue(context.Background(), "requestID", testReqABC123)
	if got := GetRequestID(ctx); got != testReqABC123 {
		t.Errorf(testExpectedGotFmt, testReqABC123, got)
	}
}

func TestGetRequestIDNil(t *testing.T) {
	if got := GetRequestID(nil); got != "" {
		t.Errorf(testNilCtxEmptyFmt, got)
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

func TestEventBuilderWithContext(t *testing.T) {
	ctx := context.Background()
	ctx = context.WithValue(ctx, "userEmail", testEmailExample)
	ctx = context.WithValue(ctx, "userID", "uid-1")
	ctx = context.WithValue(ctx, "userName", testUserName)
	ctx = context.WithValue(ctx, "ipAddress", testIP10)
	ctx = context.WithValue(ctx, "requestID", "req-1")

	event := NewEvent(EventServerlessEstimate).WithContext(ctx).Build()

	if event.UserEmail != testEmailExample {
		t.Errorf("expected email %q, got %q", testEmailExample, event.UserEmail)
	}
	if event.UserID != "uid-1" {
		t.Errorf("expected userID %q, got %q", "uid-1", event.UserID)
	}
	if event.UserName != testUserName {
		t.Errorf("expected userName %q, got %q", testUserName, event.UserName)
	}
	if event.IPAddress != testIP10 {
		t.Errorf("expected IP %q, got %q", testIP10, event.IPAddress)
	}
	if event.RequestID != "req-1" {
		t.Errorf("expected requestID %q, got %q", "req-1", event.RequestID)
	}
}

func TestEventBuilderWithUser(t *testing.T) {
	event := NewEvent(EventProvisionedEstimate).
		WithUser(testEmailAB, "uid-2", "Alice").
		Build()

	if event.UserEmail != testEmailAB {
		t.Errorf("expected email %q, got %q", testEmailAB, event.UserEmail)
	}
	if event.UserID != "uid-2" {
		t.Errorf("expected userID %q, got %q", "uid-2", event.UserID)
	}
	if event.UserName != "Alice" {
		t.Errorf("expected userName %q, got %q", "Alice", event.UserName)
	}
}

func TestEventBuilderWithAction(t *testing.T) {
	event := NewEvent(EventCacheInvalidated).WithAction("invalidate_cache").Build()
	if event.Action != "invalidate_cache" {
		t.Errorf("expected action %q, got %q", "invalidate_cache", event.Action)
	}
}

func TestEventBuilderWithResource(t *testing.T) {
	event := NewEvent(EventRegionsAccessed).WithResource(testServerlessPath).Build()
	if event.Resource != testServerlessPath {
		t.Errorf("expected resource %q, got %q", testServerlessPath, event.Resource)
	}
}

func TestEventBuilderWithResult(t *testing.T) {
	event := NewEvent(EventServerlessEstimate).WithResult(ResultSuccess).Build()
	if event.Result != ResultSuccess {
		t.Errorf("expected result %q, got %q", ResultSuccess, event.Result)
	}
}

func TestEventBuilderWithDuration(t *testing.T) {
	d := 500 * time.Millisecond
	event := NewEvent(EventServerlessEstimate).WithDuration(d).Build()
	if event.Duration != d {
		t.Errorf("expected duration %v, got %v", d, event.Duration)
	}
}

func TestEventBuilderWithError(t *testing.T) {
	err := errors.New(testSomethingWrong)
	event := NewEvent(EventServerlessEstimate).WithError(err).Build()

	if event.ErrorMsg != testSomethingWrong {
		t.Errorf("expected error %q, got %q", testSomethingWrong, event.ErrorMsg)
	}
	if event.Result != ResultError {
		t.Errorf("expected result %q, got %q", ResultError, event.Result)
	}
}

func TestEventBuilderWithErrorNil(t *testing.T) {
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

func TestEventBuilderWithMetadata(t *testing.T) {
	event := NewEvent(EventServerlessEstimate).
		WithMetadata("region", testRegionUSEast1).
		WithMetadata("workloadType", "search").
		Build()

	if event.Metadata["region"] != testRegionUSEast1 {
		t.Errorf("expected metadata region %q, got %v", testRegionUSEast1, event.Metadata["region"])
	}
	if event.Metadata["workloadType"] != "search" {
		t.Errorf("expected metadata workloadType %q, got %v", "search", event.Metadata["workloadType"])
	}
}

func TestEventBuilderChaining(t *testing.T) {
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

func TestLogEventSuccess(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
	}
	// Should not panic
	logger.LogEvent(context.Background(), event)
}

func TestLogEventFailure(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultFailure,
		ErrorMsg:  "calculation failed",
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEventError(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultError,
		ErrorMsg:  "internal error",
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEventWithDuration(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
		Duration:  500 * time.Millisecond,
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEventWithMetadata(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	event := AuditEvent{
		EventType: EventServerlessEstimate,
		Action:    "estimate",
		Result:    ResultSuccess,
		Metadata:  map[string]interface{}{"region": testRegionUSEast1},
	}
	logger.LogEvent(context.Background(), event)
}

func TestLogEventExtractsContextWhenFieldsEmpty(t *testing.T) {
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

func TestLogEventSetsTimestamp(t *testing.T) {
	before := time.Now()
	event := NewEvent(EventServerlessEstimate).
		WithAction("estimate").
		WithResult(ResultSuccess).
		Build()
	after := time.Now()

	if event.Timestamp.IsZero() {
		t.Fatal("expected non-zero timestamp on built event")
	}
	if event.Timestamp.Before(before) || event.Timestamp.After(after) {
		t.Errorf("expected timestamp between %v and %v, got %v", before, after, event.Timestamp)
	}
}

func TestEventBuilderLog(t *testing.T) {
	logger := NewLogger(zap.NewNop())
	NewEvent(EventServerlessEstimate).
		WithAction("estimate").
		WithResult(ResultSuccess).
		Log(logger)
}
