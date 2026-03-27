// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"sync"
	"time"

	"github.com/google/uuid"
)

// ConversationCache manages conversation sessions with automatic cleanup
type ConversationCache struct {
	sessions map[string]*ConversationSession
	mu       sync.RWMutex
	ttl      time.Duration
	stopCh   chan struct{}
}

// NewConversationCache creates a new conversation cache with the specified TTL
func NewConversationCache(ttl time.Duration) *ConversationCache {
	cache := &ConversationCache{
		sessions: make(map[string]*ConversationSession),
		ttl:      ttl,
		stopCh:   make(chan struct{}),
	}

	// Start background cleanup goroutine
	go cache.cleanup()

	return cache
}

// isValidUUID checks if a string is a valid UUID format
func isValidUUID(s string) bool {
	_, err := uuid.Parse(s)
	return err == nil
}

// GetOrCreate retrieves an existing conversation session or creates a new one.
// If sessionID is provided, it must be a valid UUID; invalid IDs are ignored
// and a new session is created instead.
func (c *ConversationCache) GetOrCreate(sessionID string) *ConversationSession {
	c.mu.Lock()
	defer c.mu.Unlock()

	// If session ID is provided and valid, look it up
	if sessionID != "" && isValidUUID(sessionID) {
		if session, exists := c.sessions[sessionID]; exists {
			// Update expiration time
			session.UpdatedAt = time.Now()
			session.ExpiresAt = time.Now().Add(c.ttl)
			return session
		}
	}

	// Create new session with a generated UUID
	now := time.Now()
	newSessionID := uuid.New().String()

	session := &ConversationSession{
		ID:        newSessionID,
		Messages:  []ConversationMessage{},
		CreatedAt: now,
		UpdatedAt: now,
		ExpiresAt: now.Add(c.ttl),
	}

	c.sessions[newSessionID] = session
	return session
}

// Get retrieves a conversation session by ID
func (c *ConversationCache) Get(sessionID string) (*ConversationSession, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	session, exists := c.sessions[sessionID]
	if !exists {
		return nil, false
	}

	// Check if expired
	if time.Now().After(session.ExpiresAt) {
		return nil, false
	}

	return session, true
}

// Update updates a conversation session
func (c *ConversationCache) Update(session *ConversationSession) {
	c.mu.Lock()
	defer c.mu.Unlock()

	session.UpdatedAt = time.Now()
	session.ExpiresAt = time.Now().Add(c.ttl)
	c.sessions[session.ID] = session
}

// AddMessage adds a message to a conversation session
func (c *ConversationCache) AddMessage(sessionID string, role, content string) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	session, exists := c.sessions[sessionID]
	if !exists {
		return nil // Session doesn't exist, ignore
	}

	message := ConversationMessage{
		Role:      role,
		Content:   content,
		Timestamp: time.Now(),
	}

	session.Messages = append(session.Messages, message)
	session.UpdatedAt = time.Now()
	session.ExpiresAt = time.Now().Add(c.ttl)

	return nil
}

// Delete removes a conversation session
func (c *ConversationCache) Delete(sessionID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.sessions, sessionID)
}

// Clear removes all conversation sessions
func (c *ConversationCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sessions = make(map[string]*ConversationSession)
}

// Size returns the number of active sessions
func (c *ConversationCache) Size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.sessions)
}

// cleanup runs periodically to remove expired sessions
func (c *ConversationCache) cleanup() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			c.removeExpired()
		case <-c.stopCh:
			return
		}
	}
}

// removeExpired removes all expired sessions
func (c *ConversationCache) removeExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for id, session := range c.sessions {
		if now.After(session.ExpiresAt) {
			delete(c.sessions, id)
		}
	}
}

// Stop stops the background cleanup goroutine
func (c *ConversationCache) Stop() {
	close(c.stopCh)
}

// GetRecentMessages returns the N most recent messages from a session
func (c *ConversationCache) GetRecentMessages(sessionID string, count int) []ConversationMessage {
	c.mu.RLock()
	defer c.mu.RUnlock()

	session, exists := c.sessions[sessionID]
	if !exists {
		return []ConversationMessage{}
	}

	messages := session.Messages
	if len(messages) <= count {
		return messages
	}

	// Return last N messages
	return messages[len(messages)-count:]
}
