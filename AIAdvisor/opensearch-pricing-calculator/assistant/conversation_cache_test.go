// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"testing"
	"time"
)

// Valid UUID for use in tests
const testSessionID = "550e8400-e29b-41d4-a716-446655440000"
const testSessionID2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

func TestNewConversationCache(t *testing.T) {
	cache := NewConversationCache(5 * time.Minute)
	defer cache.Stop()

	if cache == nil {
		t.Fatal("expected non-nil cache")
	}
	if cache.Size() != 0 {
		t.Errorf("expected size 0, got %d", cache.Size())
	}
}

func TestConversationCache_GetOrCreate_NewSession(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	session := cache.GetOrCreate(testSessionID)
	if session == nil {
		t.Fatal("expected non-nil session")
	}
	// Valid UUID provided but no existing session — a new session is created with a generated ID
	if session.ID == "" {
		t.Error("expected non-empty session ID")
	}
	if len(session.Messages) != 0 {
		t.Errorf("expected 0 messages, got %d", len(session.Messages))
	}
}

func TestConversationCache_GetOrCreate_ExistingSession(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	session1 := cache.GetOrCreate(testSessionID)
	session1.Messages = append(session1.Messages, ConversationMessage{Role: "user", Content: "hello"})

	// Look up by the actual assigned ID
	session2 := cache.GetOrCreate(session1.ID)
	if len(session2.Messages) != 1 {
		t.Errorf("expected 1 message from existing session, got %d", len(session2.Messages))
	}
}

func TestConversationCache_GetOrCreate_InvalidUUID(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	// Invalid session ID should be ignored; a new session is created with a generated UUID
	session := cache.GetOrCreate("not-a-uuid")
	if session == nil {
		t.Fatal("expected non-nil session")
	}
	if session.ID == "not-a-uuid" {
		t.Error("expected invalid session ID to be replaced with a generated UUID")
	}
	if !isValidUUID(session.ID) {
		t.Errorf("expected generated session ID to be a valid UUID, got %q", session.ID)
	}
}

func TestConversationCache_GetOrCreate_EmptyID(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	session := cache.GetOrCreate("")
	if session.ID == "" {
		t.Error("expected auto-generated session ID")
	}
}

func TestConversationCache_Get(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	created := cache.GetOrCreate(testSessionID)
	session, found := cache.Get(created.ID)
	if !found {
		t.Fatal("expected to find session")
	}
	if session.ID != created.ID {
		t.Errorf("expected ID %q, got %q", created.ID, session.ID)
	}
}

func TestConversationCache_Get_NotFound(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	_, found := cache.Get("nonexistent")
	if found {
		t.Error("expected not to find nonexistent session")
	}
}

func TestConversationCache_Get_Expired(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      1 * time.Millisecond,
		stopCh:   make(chan struct{}),
	}

	created := cache.GetOrCreate(testSessionID)
	time.Sleep(5 * time.Millisecond)

	_, found := cache.Get(created.ID)
	if found {
		t.Error("expected expired session to not be found")
	}
}

func TestConversationCache_Update(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	session := cache.GetOrCreate(testSessionID)
	session.Messages = append(session.Messages, ConversationMessage{Role: "user", Content: "hello"})
	cache.Update(session)

	retrieved, found := cache.Get(session.ID)
	if !found {
		t.Fatal("expected to find updated session")
	}
	if len(retrieved.Messages) != 1 {
		t.Errorf("expected 1 message, got %d", len(retrieved.Messages))
	}
}

func TestConversationCache_AddMessage(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	created := cache.GetOrCreate(testSessionID)
	err := cache.AddMessage(created.ID, "user", "hello")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	session, _ := cache.Get(created.ID)
	if len(session.Messages) != 1 {
		t.Fatalf("expected 1 message, got %d", len(session.Messages))
	}
	if session.Messages[0].Role != "user" {
		t.Errorf("expected role %q, got %q", "user", session.Messages[0].Role)
	}
	if session.Messages[0].Content != "hello" {
		t.Errorf("expected content %q, got %q", "hello", session.Messages[0].Content)
	}
}

func TestConversationCache_AddMessage_NonexistentSession(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	err := cache.AddMessage("nonexistent", "user", "hello")
	if err != nil {
		t.Error("expected nil error for nonexistent session")
	}
}

func TestConversationCache_Delete(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	created := cache.GetOrCreate(testSessionID)
	cache.Delete(created.ID)
	if cache.Size() != 0 {
		t.Errorf("expected size 0 after delete, got %d", cache.Size())
	}
}

func TestConversationCache_Clear(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	cache.GetOrCreate(testSessionID)
	cache.GetOrCreate(testSessionID2)
	cache.Clear()
	if cache.Size() != 0 {
		t.Errorf("expected size 0 after clear, got %d", cache.Size())
	}
}

func TestConversationCache_GetRecentMessages(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	created := cache.GetOrCreate(testSessionID)
	for i := 0; i < 5; i++ {
		cache.AddMessage(created.ID, "user", "message")
	}

	// Get last 3 messages
	msgs := cache.GetRecentMessages(created.ID, 3)
	if len(msgs) != 3 {
		t.Errorf("expected 3 messages, got %d", len(msgs))
	}
}

func TestConversationCache_GetRecentMessages_LessThanCount(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	created := cache.GetOrCreate(testSessionID)
	cache.AddMessage(created.ID, "user", "only one")

	msgs := cache.GetRecentMessages(created.ID, 5)
	if len(msgs) != 1 {
		t.Errorf("expected 1 message, got %d", len(msgs))
	}
}

func TestConversationCache_GetRecentMessages_NonexistentSession(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      5 * time.Minute,
		stopCh:   make(chan struct{}),
	}

	msgs := cache.GetRecentMessages("nonexistent", 5)
	if len(msgs) != 0 {
		t.Errorf("expected 0 messages, got %d", len(msgs))
	}
}

func TestConversationCache_RemoveExpired(t *testing.T) {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      1 * time.Millisecond,
		stopCh:   make(chan struct{}),
	}

	cache.GetOrCreate(testSessionID)
	cache.GetOrCreate(testSessionID2)
	time.Sleep(5 * time.Millisecond)

	cache.removeExpired()
	if cache.Size() != 0 {
		t.Errorf("expected all expired sessions removed, got %d", cache.Size())
	}
}

func TestConversationCache_Stop(t *testing.T) {
	cache := NewConversationCache(5 * time.Minute)
	cache.Stop() // Should not panic
}

func TestIsValidUUID(t *testing.T) {
	tests := []struct {
		input string
		want  bool
	}{
		{"550e8400-e29b-41d4-a716-446655440000", true},
		{"6ba7b810-9dad-11d1-80b4-00c04fd430c8", true},
		{"not-a-uuid", false},
		{"", false},
		{"test-session", false},
		{"12345", false},
	}

	for _, tt := range tests {
		if got := isValidUUID(tt.input); got != tt.want {
			t.Errorf("isValidUUID(%q) = %v, want %v", tt.input, got, tt.want)
		}
	}
}
