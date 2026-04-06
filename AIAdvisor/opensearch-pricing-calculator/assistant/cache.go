// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"crypto/md5"
	"encoding/hex"
	"sync"
	"time"
)

// CacheEntry represents a cached estimate response
type CacheEntry struct {
	Response  *EstimateResponse
	ExpiresAt time.Time
}

// AssistantCache provides in-memory caching for assistant responses
type AssistantCache struct {
	entries map[string]*CacheEntry
	mu      sync.RWMutex
	ttl     time.Duration
}

// NewAssistantCache creates a new cache instance
func NewAssistantCache(ttl time.Duration) *AssistantCache {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     ttl,
	}

	// Start cleanup goroutine
	go cache.cleanupLoop()

	return cache
}

// Get retrieves a cached response by query
func (c *AssistantCache) Get(query string) (*EstimateResponse, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	key := c.generateKey(query)
	entry, exists := c.entries[key]

	if !exists {
		return nil, false
	}

	// Check if entry has expired
	if time.Now().After(entry.ExpiresAt) {
		return nil, false
	}

	return entry.Response, true
}

// Set stores a response in the cache
func (c *AssistantCache) Set(query string, response *EstimateResponse) {
	c.mu.Lock()
	defer c.mu.Unlock()

	key := c.generateKey(query)
	c.entries[key] = &CacheEntry{
		Response:  response,
		ExpiresAt: time.Now().Add(c.ttl),
	}
}

// Invalidate removes a specific entry from cache
func (c *AssistantCache) Invalidate(query string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	key := c.generateKey(query)
	delete(c.entries, key)
}

// Clear removes all entries from cache
func (c *AssistantCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.entries = make(map[string]*CacheEntry)
}

// Size returns the number of entries in cache
func (c *AssistantCache) Size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return len(c.entries)
}

// generateKey creates a cache key from a query string
func (c *AssistantCache) generateKey(query string) string {
	// Normalize query (lowercase, trim spaces)
	normalized := normalizeQuery(query)

	// Generate MD5 hash
	hash := md5.Sum([]byte(normalized))
	return hex.EncodeToString(hash[:])
}

// cleanupLoop periodically removes expired entries
func (c *AssistantCache) cleanupLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		c.cleanup()
	}
}

// cleanup removes expired entries from cache
func (c *AssistantCache) cleanup() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for key, entry := range c.entries {
		if now.After(entry.ExpiresAt) {
			delete(c.entries, key)
		}
	}
}

// normalizeQuery normalizes a query string for consistent cache keys
func normalizeQuery(query string) string {
	// For simplicity, we'll just use lowercase and trim
	// A more sophisticated approach could remove extra whitespace,
	// normalize numbers, etc.
	return query // Using exact match for now
}
