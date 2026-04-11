// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/opensearch-project/opensearch-pricing-calculator/cors"
	"go.uber.org/zap"
)

// RateLimiter implements a token bucket rate limiter per IP address.
type RateLimiter struct {
	mu       sync.RWMutex
	buckets  map[string]*tokenBucket
	rate     int           // tokens per interval
	interval time.Duration // refill interval
	burst    int           // max tokens
}

type tokenBucket struct {
	tokens     int
	lastRefill time.Time
}

// NewRateLimiter creates a new rate limiter.
// rate: number of requests allowed per interval
// interval: time period for rate limit
// burst: maximum number of requests in a burst
func NewRateLimiter(rate int, interval time.Duration, burst int) *RateLimiter {
	rl := &RateLimiter{
		buckets:  make(map[string]*tokenBucket),
		rate:     rate,
		interval: interval,
		burst:    burst,
	}

	// Start cleanup goroutine to remove stale entries
	go rl.cleanup()

	return rl
}

// Allow checks if a request from the given IP should be allowed.
func (rl *RateLimiter) Allow(ip string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	bucket, exists := rl.buckets[ip]

	if !exists {
		// New IP, create bucket with full tokens
		rl.buckets[ip] = &tokenBucket{
			tokens:     rl.burst - 1, // consume one token
			lastRefill: now,
		}
		return true
	}

	// Refill tokens based on elapsed time
	elapsed := now.Sub(bucket.lastRefill)
	tokensToAdd := int(elapsed/rl.interval) * rl.rate

	if tokensToAdd > 0 {
		bucket.tokens = min(bucket.tokens+tokensToAdd, rl.burst)
		bucket.lastRefill = now
	}

	// Check if we have tokens available
	if bucket.tokens > 0 {
		bucket.tokens--
		return true
	}

	return false
}

// cleanup removes stale entries every minute
func (rl *RateLimiter) cleanup() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		rl.mu.Lock()
		staleThreshold := time.Now().Add(-5 * time.Minute)
		for ip, bucket := range rl.buckets {
			if bucket.lastRefill.Before(staleThreshold) {
				delete(rl.buckets, ip)
			}
		}
		rl.mu.Unlock()
	}
}

// Global rate limiters with different limits for different endpoint types
var (
	// General API rate limiter: 100 requests per minute per IP
	generalRateLimiter = NewRateLimiter(100, time.Minute, 150)

	// Sensitive operations rate limiter: 10 requests per minute per IP
	sensitiveRateLimiter = NewRateLimiter(10, time.Minute, 15)

	// Cache invalidation rate limiter: 5 requests per hour per IP
	cacheInvalidationRateLimiter = NewRateLimiter(5, time.Hour, 10)
)

func init() {
	// Allow custom rate limits via environment variables
	if rate := os.Getenv("RATE_LIMIT_GENERAL"); rate != "" {
		if r, err := strconv.Atoi(rate); err == nil {
			generalRateLimiter = NewRateLimiter(r, time.Minute, r*2)
		}
	}
	if rate := os.Getenv("RATE_LIMIT_SENSITIVE"); rate != "" {
		if r, err := strconv.Atoi(rate); err == nil {
			sensitiveRateLimiter = NewRateLimiter(r, time.Minute, r*2)
		}
	}
}

// MaxBodySize limits request body size to prevent memory exhaustion from oversized payloads.
const MaxBodySize = 1 << 20 // 1 MB

// LimitRequestBody is a middleware that caps the request body size.
func (app *application) LimitRequestBody(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, MaxBodySize)
		}
		h.ServeHTTP(w, r)
	})
}

// EnableCors is a middleware that adds Cross-Origin Resource Sharing (CORS) headers to the request.
// It validates the Origin header against an allowlist of trusted domains.
// Additionally, it sets the Access-Control-Allow-Methods, Access-Control-Allow-Headers, and
// Access-Control-Allow-Credentials headers to the response if the request method is OPTIONS.
func (app *application) EnableCors(h http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		origin := request.Header.Get("Origin")

		// Only set CORS headers if the origin is in the allowed list
		if origin != "" && cors.IsOriginAllowed(origin) {
			writer.Header().Set("Access-Control-Allow-Origin", origin)
			writer.Header().Set("Access-Control-Allow-Credentials", "true")
			// Expose X-Session-ID so frontend can read it from response
			writer.Header().Set("Access-Control-Expose-Headers", "X-Session-ID")
		}

		if request.Method == "OPTIONS" {
			if origin != "" && cors.IsOriginAllowed(origin) {
				writer.Header().Set("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE,PATCH,OPTIONS")
				// Allow X-Session-ID header to be sent from frontend
				writer.Header().Set("Access-Control-Allow-Headers", "Accept, Content-Type, X-CSRF-Token, Authorization, X-Session-ID, X-User-ID, X-User-Email, X-User-Name")
			}
			writer.WriteHeader(http.StatusNoContent)
			return
		}

		h.ServeHTTP(writer, request)
	})
}

// getClientIP extracts the client IP from the request.
// It checks X-Forwarded-For and X-Real-IP headers first (for proxied requests),
// then falls back to RemoteAddr.
func getClientIP(r *http.Request) string {
	// Check X-Forwarded-For header (may contain multiple IPs)
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		// Take the first IP (original client)
		if idx := strings.Index(xff, ","); idx != -1 {
			return strings.TrimSpace(xff[:idx])
		}
		return strings.TrimSpace(xff)
	}

	// Check X-Real-IP header
	if xri := r.Header.Get("X-Real-IP"); xri != "" {
		return strings.TrimSpace(xri)
	}

	// Fall back to RemoteAddr (strip port if present)
	ip := r.RemoteAddr
	if idx := strings.LastIndex(ip, ":"); idx != -1 {
		ip = ip[:idx]
	}
	return ip
}

// RateLimit is a middleware that applies the general rate limit to all requests.
func (app *application) RateLimit(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := getClientIP(r)

		if !generalRateLimiter.Allow(ip) {
			app.logger.Warn("rate limit exceeded",
				zap.String("ip", ip),
				zap.String("path", r.URL.Path),
				zap.String("limiter", "general"),
			)
			w.Header().Set("Retry-After", "60")
			http.Error(w, "Rate limit exceeded. Please try again later.", http.StatusTooManyRequests)
			return
		}

		h.ServeHTTP(w, r)
	})
}

// RateLimitSensitive is a middleware for sensitive operations with stricter limits.
func (app *application) RateLimitSensitive(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := getClientIP(r)

		if !sensitiveRateLimiter.Allow(ip) {
			app.logger.Warn("sensitive rate limit exceeded",
				zap.String("ip", ip),
				zap.String("path", r.URL.Path),
				zap.String("limiter", "sensitive"),
			)
			w.Header().Set("Retry-After", "60")
			http.Error(w, "Rate limit exceeded for this operation. Please try again later.", http.StatusTooManyRequests)
			return
		}

		next(w, r)
	}
}

// RateLimitCacheInvalidation is a middleware for cache invalidation with very strict limits.
func (app *application) RateLimitCacheInvalidation(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := getClientIP(r)

		if !cacheInvalidationRateLimiter.Allow(ip) {
			app.logger.Warn("cache invalidation rate limit exceeded",
				zap.String("ip", ip),
				zap.String("path", r.URL.Path),
				zap.String("limiter", "cache_invalidation"),
			)
			w.Header().Set("Retry-After", "3600")
			http.Error(w, "Cache invalidation rate limit exceeded. Please try again later.", http.StatusTooManyRequests)
			return
		}

		next(w, r)
	}
}
