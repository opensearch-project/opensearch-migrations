// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
	"time"
)

// LLMCacheEntry represents a cached LLM response
type LLMCacheEntry struct {
	Response  *EnhancedLLMResponse
	CachedAt  time.Time
	ExpiresAt time.Time
	HitCount  int // Track how many times this cache entry was used
}

// LLMCache manages cached LLM responses to reduce API calls and costs
type LLMCache struct {
	entries map[string]*LLMCacheEntry
	mu      sync.RWMutex
	ttl     time.Duration
	stopCh  chan struct{}
}

// NewLLMCache creates a new LLM response cache with the specified TTL
func NewLLMCache(ttl time.Duration) *LLMCache {
	cache := &LLMCache{
		entries: make(map[string]*LLMCacheEntry),
		ttl:     ttl,
		stopCh:  make(chan struct{}),
	}

	// Start background cleanup goroutine
	go cache.cleanup()

	return cache
}

// Get retrieves a cached LLM response by query and context
func (c *LLMCache) Get(query string, conversationContext []ConversationMessage) (*EnhancedLLMResponse, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	key := c.generateKey(query, conversationContext)
	entry, exists := c.entries[key]

	if !exists {
		return nil, false
	}

	// Check if expired
	if time.Now().After(entry.ExpiresAt) {
		delete(c.entries, key)
		return nil, false
	}

	// Increment hit count
	entry.HitCount++

	return entry.Response, true
}

// Set stores an LLM response in the cache
func (c *LLMCache) Set(query string, conversationContext []ConversationMessage, response *EnhancedLLMResponse) {
	c.mu.Lock()
	defer c.mu.Unlock()

	key := c.generateKey(query, conversationContext)
	now := time.Now()

	c.entries[key] = &LLMCacheEntry{
		Response:  response,
		CachedAt:  now,
		ExpiresAt: now.Add(c.ttl),
		HitCount:  0,
	}
}

// Delete removes a cached LLM response
func (c *LLMCache) Delete(query string, conversationContext []ConversationMessage) {
	c.mu.Lock()
	defer c.mu.Unlock()

	key := c.generateKey(query, conversationContext)
	delete(c.entries, key)
}

// Clear removes all cached LLM responses
func (c *LLMCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[string]*LLMCacheEntry)
}

// Size returns the number of cached entries
func (c *LLMCache) Size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.entries)
}

// GetStats returns cache statistics
func (c *LLMCache) GetStats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	totalHits := 0
	for _, entry := range c.entries {
		totalHits += entry.HitCount
	}

	return map[string]interface{}{
		"size":       len(c.entries),
		"totalHits":  totalHits,
		"ttlMinutes": c.ttl.Minutes(),
	}
}

// cleanup runs periodically to remove expired entries
func (c *LLMCache) cleanup() {
	ticker := time.NewTicker(2 * time.Minute)
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

// removeExpired removes all expired cache entries
func (c *LLMCache) removeExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for key, entry := range c.entries {
		if now.After(entry.ExpiresAt) {
			delete(c.entries, key)
		}
	}
}

// Stop stops the background cleanup goroutine
func (c *LLMCache) Stop() {
	close(c.stopCh)
}

// generateKey creates a cache key from query and conversation context
// The key is a SHA256 hash of the normalized query + conversation context
func (c *LLMCache) generateKey(query string, conversationContext []ConversationMessage) string {
	// Normalize query: lowercase, trim whitespace, collapse multiple spaces
	normalizedQuery := strings.ToLower(strings.TrimSpace(query))
	normalizedQuery = strings.Join(strings.Fields(normalizedQuery), " ")

	// Create a unique key that includes conversation context
	// This ensures that the same query with different contexts gets different cache entries
	var keyData struct {
		Query   string
		Context []string
	}

	keyData.Query = normalizedQuery

	// Include last N messages from context (simplified representation)
	// We only include the content, not timestamps, to allow cache hits
	// even if the exact timestamps differ
	if len(conversationContext) > 0 {
		keyData.Context = make([]string, 0, len(conversationContext))
		for _, msg := range conversationContext {
			// Format: "role:content"
			keyData.Context = append(keyData.Context, msg.Role+":"+msg.Content)
		}
	}

	// JSON encode and hash
	jsonData, _ := json.Marshal(keyData)
	hash := sha256.Sum256(jsonData)
	return hex.EncodeToString(hash[:])
}

// InvalidateByPattern removes cache entries matching a pattern
// This can be used to invalidate all caches for a specific workload type, region, etc.
func (c *LLMCache) InvalidateByPattern(pattern string) int {
	c.mu.Lock()
	defer c.mu.Unlock()

	pattern = strings.ToLower(pattern)
	removed := 0

	for key, entry := range c.entries {
		// Check if the response contains the pattern
		responseJSON, _ := json.Marshal(entry.Response)
		responseStr := strings.ToLower(string(responseJSON))

		if strings.Contains(responseStr, pattern) {
			delete(c.entries, key)
			removed++
		}
	}

	return removed
}
