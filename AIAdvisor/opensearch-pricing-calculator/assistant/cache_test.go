// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"testing"
	"time"
)

func TestNewAssistantCache(t *testing.T) {
	cache := NewAssistantCache(5 * time.Minute)
	if cache == nil {
		t.Fatal("expected non-nil cache")
	}
	if cache.Size() != 0 {
		t.Errorf("expected size 0, got %d", cache.Size())
	}
}

func TestAssistantCache_SetAndGet(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	resp := &EstimateResponse{Success: true, WorkloadType: "search"}
	cache.Set("test query", resp)

	got, found := cache.Get("test query")
	if !found {
		t.Fatal("expected to find cached entry")
	}
	if got.WorkloadType != "search" {
		t.Errorf("expected workloadType %q, got %q", "search", got.WorkloadType)
	}
}

func TestAssistantCache_GetMiss(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	_, found := cache.Get("nonexistent")
	if found {
		t.Error("expected miss for nonexistent key")
	}
}

func TestAssistantCache_GetExpired(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     1 * time.Millisecond,
	}

	cache.Set("test", &EstimateResponse{Success: true})
	time.Sleep(5 * time.Millisecond) // Wait for expiration

	_, found := cache.Get("test")
	if found {
		t.Error("expected expired entry to not be found")
	}
}

func TestAssistantCache_Invalidate(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	cache.Set("test", &EstimateResponse{Success: true})
	if cache.Size() != 1 {
		t.Fatalf("expected size 1, got %d", cache.Size())
	}

	cache.Invalidate("test")
	_, found := cache.Get("test")
	if found {
		t.Error("expected entry to be invalidated")
	}
}

func TestAssistantCache_Clear(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	cache.Set("a", &EstimateResponse{})
	cache.Set("b", &EstimateResponse{})
	if cache.Size() != 2 {
		t.Fatalf("expected size 2, got %d", cache.Size())
	}

	cache.Clear()
	if cache.Size() != 0 {
		t.Errorf("expected size 0 after clear, got %d", cache.Size())
	}
}

func TestAssistantCache_Size(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	if cache.Size() != 0 {
		t.Errorf("expected size 0, got %d", cache.Size())
	}
	cache.Set("a", &EstimateResponse{})
	if cache.Size() != 1 {
		t.Errorf("expected size 1, got %d", cache.Size())
	}
}

func TestAssistantCache_GenerateKey_Deterministic(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	key1 := cache.generateKey("test query")
	key2 := cache.generateKey("test query")
	if key1 != key2 {
		t.Errorf("expected same key for same input, got %q and %q", key1, key2)
	}
}

func TestAssistantCache_GenerateKey_DifferentInputs(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	key1 := cache.generateKey("query one")
	key2 := cache.generateKey("query two")
	if key1 == key2 {
		t.Error("expected different keys for different inputs")
	}
}

func TestNormalizeQuery(t *testing.T) {
	result := normalizeQuery("test query")
	if result != "test query" {
		t.Errorf("expected %q, got %q", "test query", result)
	}
}

func TestAssistantCache_Cleanup(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     1 * time.Millisecond,
	}

	cache.Set("a", &EstimateResponse{})
	cache.Set("b", &EstimateResponse{})
	time.Sleep(5 * time.Millisecond)

	cache.cleanup()
	if cache.Size() != 0 {
		t.Errorf("expected all expired entries removed, got %d", cache.Size())
	}
}

func TestAssistantCache_Cleanup_KeepsNonExpired(t *testing.T) {
	cache := &AssistantCache{
		entries: make(map[string]*CacheEntry),
		ttl:     5 * time.Minute,
	}

	cache.Set("a", &EstimateResponse{})
	cache.cleanup()
	if cache.Size() != 1 {
		t.Errorf("expected non-expired entry kept, got size %d", cache.Size())
	}
}
