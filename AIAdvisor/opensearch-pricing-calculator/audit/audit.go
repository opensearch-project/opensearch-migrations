// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package audit

import (
	"context"
	"encoding/json"
	"time"

	"go.uber.org/zap"
)

// EventType represents the type of audit event
type EventType string

const (
	// Assistant events
	EventAssistantQuery        EventType = "ASSISTANT_QUERY"
	EventAssistantEstimate     EventType = "ASSISTANT_ESTIMATE"
	EventAssistantCacheCleared EventType = "ASSISTANT_CACHE_CLEARED"

	// Estimate events
	EventProvisionedEstimate EventType = "PROVISIONED_ESTIMATE"
	EventServerlessEstimate  EventType = "SERVERLESS_ESTIMATE"

	// Cache events
	EventCacheInvalidated EventType = "CACHE_INVALIDATED"

	// Data access events
	EventRegionsAccessed       EventType = "REGIONS_ACCESSED"
	EventPricingDataAccessed   EventType = "PRICING_DATA_ACCESSED"
	EventInstanceTypesAccessed EventType = "INSTANCE_TYPES_ACCESSED"
)

// Result represents the outcome of an audited operation
type Result string

const (
	ResultSuccess Result = "SUCCESS"
	ResultFailure Result = "FAILURE"
	ResultError   Result = "ERROR"
)

// AuditEvent represents a structured audit log entry
type AuditEvent struct {
	Timestamp time.Time              `json:"timestamp"`
	EventType EventType              `json:"eventType"`
	UserEmail string                 `json:"userEmail,omitempty"`
	UserID    string                 `json:"userID,omitempty"`
	UserName  string                 `json:"userName,omitempty"`
	Action    string                 `json:"action"`
	Resource  string                 `json:"resource,omitempty"`
	Result    Result                 `json:"result"`
	RequestID string                 `json:"requestID,omitempty"`
	IPAddress string                 `json:"ipAddress,omitempty"`
	Duration  time.Duration          `json:"durationMs,omitempty"`
	Metadata  map[string]interface{} `json:"metadata,omitempty"`
	ErrorMsg  string                 `json:"errorMessage,omitempty"`
}

// Logger provides audit logging functionality
type Logger struct {
	logger *zap.Logger
}

// NewLogger creates a new audit logger
func NewLogger(logger *zap.Logger) *Logger {
	return &Logger{
		logger: logger.Named("audit"),
	}
}

// LogEvent logs an audit event with structured data
func (l *Logger) LogEvent(ctx context.Context, event AuditEvent) {
	// Set timestamp if not provided
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}

	// Extract user context from context if not provided
	if event.UserEmail == "" {
		event.UserEmail = GetUserEmail(ctx)
	}
	if event.UserID == "" {
		event.UserID = GetUserID(ctx)
	}
	if event.UserName == "" {
		event.UserName = GetUserName(ctx)
	}

	// Convert event to JSON for structured logging
	eventJSON, err := json.Marshal(event)
	if err != nil {
		l.logger.Error("Failed to marshal audit event", zap.Error(err))
		return
	}

	// Log at appropriate level based on result
	fields := []zap.Field{
		zap.String("audit_event", string(eventJSON)),
		zap.String("event_type", string(event.EventType)),
		zap.String("user_email", event.UserEmail),
		zap.String("user_id", event.UserID),
		zap.String("action", event.Action),
		zap.String("result", string(event.Result)),
		zap.String("request_id", event.RequestID),
	}

	if event.Duration > 0 {
		fields = append(fields, zap.Duration("duration", event.Duration))
	}

	if event.ErrorMsg != "" {
		fields = append(fields, zap.String("error", event.ErrorMsg))
	}

	// Add metadata fields
	if event.Metadata != nil {
		for key, value := range event.Metadata {
			fields = append(fields, zap.Any("meta_"+key, value))
		}
	}

	switch event.Result {
	case ResultSuccess:
		l.logger.Info("Audit event", fields...)
	case ResultFailure, ResultError:
		l.logger.Warn("Audit event", fields...)
	default:
		l.logger.Info("Audit event", fields...)
	}
}

// Helper functions to extract user context from context.Context

// GetUserEmail retrieves user email from context
func GetUserEmail(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	if email, ok := ctx.Value("userEmail").(string); ok {
		return email
	}
	return ""
}

// GetUserID retrieves user ID from context
func GetUserID(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	if id, ok := ctx.Value("userID").(string); ok {
		return id
	}
	return ""
}

// GetUserName retrieves user name from context
func GetUserName(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	if name, ok := ctx.Value("userName").(string); ok {
		return name
	}
	return ""
}

// GetIPAddress retrieves IP address from context
func GetIPAddress(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	if ip, ok := ctx.Value("ipAddress").(string); ok {
		return ip
	}
	return ""
}

// GetRequestID retrieves request ID from context
func GetRequestID(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	if reqID, ok := ctx.Value("requestID").(string); ok {
		return reqID
	}
	return ""
}

// Builder pattern for creating audit events

// EventBuilder helps construct audit events
type EventBuilder struct {
	event AuditEvent
}

// NewEvent creates a new audit event builder
func NewEvent(eventType EventType) *EventBuilder {
	return &EventBuilder{
		event: AuditEvent{
			Timestamp: time.Now(),
			EventType: eventType,
			Metadata:  make(map[string]interface{}),
		},
	}
}

// WithContext extracts user and request info from context
func (b *EventBuilder) WithContext(ctx context.Context) *EventBuilder {
	b.event.UserEmail = GetUserEmail(ctx)
	b.event.UserID = GetUserID(ctx)
	b.event.UserName = GetUserName(ctx)
	b.event.IPAddress = GetIPAddress(ctx)
	b.event.RequestID = GetRequestID(ctx)
	return b
}

// WithUser sets user information
func (b *EventBuilder) WithUser(email, id, name string) *EventBuilder {
	b.event.UserEmail = email
	b.event.UserID = id
	b.event.UserName = name
	return b
}

// WithAction sets the action description
func (b *EventBuilder) WithAction(action string) *EventBuilder {
	b.event.Action = action
	return b
}

// WithResource sets the resource identifier
func (b *EventBuilder) WithResource(resource string) *EventBuilder {
	b.event.Resource = resource
	return b
}

// WithResult sets the operation result
func (b *EventBuilder) WithResult(result Result) *EventBuilder {
	b.event.Result = result
	return b
}

// WithDuration sets the operation duration
func (b *EventBuilder) WithDuration(duration time.Duration) *EventBuilder {
	b.event.Duration = duration
	return b
}

// WithError sets error information
func (b *EventBuilder) WithError(err error) *EventBuilder {
	if err != nil {
		b.event.ErrorMsg = err.Error()
		b.event.Result = ResultError
	}
	return b
}

// WithMetadata adds metadata key-value pairs
func (b *EventBuilder) WithMetadata(key string, value interface{}) *EventBuilder {
	if b.event.Metadata == nil {
		b.event.Metadata = make(map[string]interface{})
	}
	b.event.Metadata[key] = value
	return b
}

// Build returns the constructed audit event
func (b *EventBuilder) Build() AuditEvent {
	return b.event
}

// Log logs the constructed audit event
func (b *EventBuilder) Log(logger *Logger) {
	logger.LogEvent(context.Background(), b.event)
}
