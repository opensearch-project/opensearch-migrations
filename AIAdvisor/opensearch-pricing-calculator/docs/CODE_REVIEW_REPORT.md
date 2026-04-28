# Go Backend Code Review Report

**Reviewer:** Senior Go Developer Analysis
**Date:** February 2026
**Codebase:** os-calculator-services (OpenSearch Cost Calculator Backend)
**Go Version:** 1.24

---

## Executive Summary

The backend codebase is **well-organized with clean separation of concerns** and follows common Go patterns. The architecture demonstrates good modularity with no circular dependencies detected. However, several areas require attention, particularly around error handling, global state management, and code duplication.

**Overall Assessment:** Production-ready with moderate code quality improvements recommended.

| Category | Rating | Notes |
|----------|--------|-------|
| Architecture | :white_check_mark: Good | Clean layered design, no circular dependencies |
| Error Handling | :warning: Needs Work | Silent failures, inconsistent patterns |
| Code Duplication | :warning: Needs Work | Duplicate functions across packages |
| Global State | :warning: Needs Work | Package-level caches complicate testing |
| Security | :white_check_mark: Good | Proper CORS, rate limiting, no hardcoded secrets |
| Testing | :large_blue_circle: Moderate | Tests exist but coverage could improve |

---

## Table of Contents

1. [Critical Issues](#1-critical-issues)
2. [High Priority Issues](#2-high-priority-issues)
3. [Medium Priority Issues](#3-medium-priority-issues)
4. [Low Priority Issues](#4-low-priority-issues)
5. [Positive Observations](#5-positive-observations)
6. [Recommendations Summary](#6-recommendations-summary)

---

## 1. Critical Issues

### 1.1 Silent Error Handling in HTTP Handlers

**Severity:** Critical
**Location:** `handler.go:89-106`, `handler.go:116-133`

The serverless estimate handlers silently swallow errors, never returning error responses to clients:

```go
// handler.go:89-106
func (app *application) ServerlessEstimate(w http.ResponseWriter, r *http.Request) {
    var request serverless.EstimateRequest
    b, err := io.ReadAll(r.Body)
    if err == nil {
        err = json.Unmarshal(b, &request)
        if err != nil {
            app.logger.Error("failed to unmarshal request", zap.Error(err))
            // BUG: Continues execution even after unmarshal error!
        }
    }
    request.Normalize()
    response, err := request.Calculate()
    if err == nil {  // Only succeeds if no error
        out, _ := json.Marshal(response)
        w.Header().Set("Content-Type", "application/json")
        w.WriteHeader(http.StatusOK)
        _, _ = w.Write(out)
    }
    // BUG: If err != nil, no response is ever sent to client!
}
```

**Impact:** Clients receive empty responses with no status code when errors occur, making debugging impossible.

**Fix:**
```go
func (app *application) ServerlessEstimate(w http.ResponseWriter, r *http.Request) {
    var request serverless.EstimateRequest
    b, err := io.ReadAll(r.Body)
    if err != nil {
        app.errorResponse(w, r, http.StatusBadRequest, "Failed to read request body")
        return
    }

    if err = json.Unmarshal(b, &request); err != nil {
        app.errorResponse(w, r, http.StatusBadRequest, "Invalid JSON")
        return
    }

    request.Normalize()
    response, err := request.Calculate()
    if err != nil {
        app.errorResponse(w, r, http.StatusInternalServerError, "Calculation failed")
        return
    }

    out, err := json.Marshal(response)
    if err != nil {
        app.errorResponse(w, r, http.StatusInternalServerError, "Response serialization failed")
        return
    }

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    _, _ = w.Write(out)
}
```

---

### 1.2 Ignored JSON Marshal Errors

**Severity:** Critical
**Location:** Multiple files (`handler.go:61-68`, `handler.go:144-147`, `handler.go:157-160`, etc.)

JSON marshaling errors are ignored throughout the codebase:

```go
// handler.go:61-68
func (app *application) Home(w http.ResponseWriter, r *http.Request) {
    // ...
    out, err := json.Marshal(payload)
    if err != nil {
        app.logger.Error("failed to marshal payload", zap.Error(err))
        // BUG: Continues to write potentially corrupt/empty data!
    }

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    _, _ = w.Write(out)  // Writing potentially nil/empty out
}
```

**Impact:** Clients may receive malformed or empty JSON responses.

**Affected Locations:**
- `handler.go:61-68` (Home)
- `handler.go:144-147` (ServerlessRegions)
- `handler.go:157-160` (InvalidateServerlessCache)
- `handler.go:169-172` (ServerlessPrice)
- `handler.go:184-187` (ProvisionedRegions)
- `handler.go:233`, `handler.go:236` (InvalidateProvisionedCache)
- `handler.go:250-253` (ProvisionedPrice)
- `handler.go:407` (ProvisionedPricingOptions)
- `handler.go:425`, `handler.go:466` (ProvisionedInstanceFamilyOptions)

---

## 2. High Priority Issues

### 2.1 Global State / Package-Level Caches

**Severity:** High
**Location:** `impl/cache/provisioned.go:25`, `impl/cache/serverless.go`, `middleware.go:98-108`

Package-level global variables make testing difficult and introduce potential thread-safety concerns:

```go
// impl/cache/provisioned.go:25
var provisionedPriceCache *ProvisionedPrice

// middleware.go:98-108
var (
    generalRateLimiter = NewRateLimiter(100, time.Minute, 150)
    sensitiveRateLimiter = NewRateLimiter(10, time.Minute, 15)
    cacheInvalidationRateLimiter = NewRateLimiter(5, time.Hour, 10)
)
```

**Impact:**
- Unit tests cannot mock caches
- Integration tests may have race conditions
- Difficult to reset state between tests

**Recommendation:** Use dependency injection:
```go
type Application struct {
    logger           *zap.Logger
    priceCache       PriceCache  // interface
    rateLimiter      RateLimiter // interface
}

func NewApplication(opts ...Option) *Application {
    // Apply options pattern
}
```

---

### 2.2 Duplicate Code Across Packages

**Severity:** High
**Location:** Multiple locations

#### 2.2.1 Duplicate `mapToStruct` Function

The same function is defined in three places:

```go
// impl/provisioned/handler.go:52-58
func mapToStruct(m map[string]interface{}, v interface{}) error {
    data, err := json.Marshal(m)
    if err != nil {
        return err
    }
    return json.Unmarshal(data, v)
}

// impl/serverless/handler.go:44-50 - IDENTICAL
// mcp/server.go:719-725 - IDENTICAL
```

**Fix:** Create shared utility package:
```go
// impl/utils/conversion.go
package utils

func MapToStruct(m map[string]interface{}, v interface{}) error {
    data, err := json.Marshal(m)
    if err != nil {
        return err
    }
    return json.Unmarshal(data, v)
}
```

#### 2.2.2 Duplicate Request Default Functions

```go
// handler.go:347-390
func createTimeSeriesRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest)
func createSearchRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest)
func createVectorRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest)

// mcp/server.go:728-738, 1187-1209 - DUPLICATE IMPLEMENTATIONS
```

---

### 2.3 Duplicate CORS/Origin Validation

**Severity:** High
**Location:** `middleware.go:126-171`, `mcp/server.go:22-68`

The same origin validation logic is duplicated:

```go
// middleware.go:126-131
var allowedOriginSuffixes = []string{
    ".aws.dev",
    ".amazonaws.com",
}

// mcp/server.go:22-25 - IDENTICAL
var allowedOriginSuffixes = []string{
    ".aws.dev",
    ".amazonaws.com",
}
```

Both files also have identical `isOriginAllowed()` and `getDefaultAllowedOrigins()` functions.

**Fix:** Centralize in a shared middleware package.

---

## 3. Medium Priority Issues

### 3.1 Inconsistent Error Variable Shadowing

**Severity:** Medium
**Location:** `handler.go:91-96`, `handler.go:118-123`

The `err` variable is reassigned without checking the previous error:

```go
// handler.go:91-96
b, err := io.ReadAll(r.Body)
if err == nil {
    err = json.Unmarshal(b, &request)  // Shadows previous err
    if err != nil {
        app.logger.Error("failed to unmarshal request", zap.Error(err))
    }
}
// err from ReadAll is lost if Unmarshal also fails
```

**Recommended Pattern:**
```go
b, err := io.ReadAll(r.Body)
if err != nil {
    app.errorResponse(w, r, http.StatusBadRequest, "Failed to read body")
    return
}

if err := json.Unmarshal(b, &request); err != nil {
    app.errorResponse(w, r, http.StatusBadRequest, "Invalid JSON")
    return
}
```

---

### 3.2 Missing Request Validation

**Severity:** Medium
**Location:** `handler.go` (multiple handlers)

Most handlers lack input validation and rely solely on JSON unmarshaling:

```go
// No validation for:
// - Maximum request size limits
// - Required field presence
// - Value range validation
// - Input sanitization
```

**Recommendation:** Add request validation middleware:
```go
func (app *application) ValidateRequestSize(maxBytes int64) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
            next.ServeHTTP(w, r)
        })
    }
}
```

---

### 3.3 Hard-coded Configuration Values

**Severity:** Medium
**Location:** Multiple files

Configuration values are scattered throughout the code:

```go
// handler.go:59
Version: "0.6.0"  // Should be injected

// assistant/llm_enhancer.go:57
modelID: "us.anthropic.claude-opus-4-5-20251101-v1:0"  // Should be configurable

// assistant/handler.go:44-47
cache: NewAssistantCache(5 * time.Minute)      // Hard-coded TTL
conversationCache: NewConversationCache(15 * time.Minute)
llmCache: NewLLMCache(30 * time.Minute)

// mcp/server.go:99-100
ReadBufferSize:  1024  // Should be configurable
WriteBufferSize: 1024
```

**Recommendation:** Create a configuration struct:
```go
type Config struct {
    Version            string
    LLMModelID         string
    CacheTTL           time.Duration
    ConversationTTL    time.Duration
    WebSocketBufferSize int
}
```

---

### 3.4 Ignored `strconv` Parse Errors

**Severity:** Medium
**Location:** `impl/cache/provisioned.go` (multiple locations)

String-to-number conversions silently ignore errors:

```go
// impl/cache/provisioned.go:420
iu.CPU, _ = strconv.Atoi(iv.(string))  // Error ignored

// impl/cache/provisioned.go:422-425
intb, err := strconv.ParseFloat(iv.(string), 64)
if err == nil {
    iu.Storage.Internal = int(intb * 1024)
}
// What happens when err != nil? Value stays at 0 silently

// impl/cache/provisioned.go:431
u.Price, _ = strconv.ParseFloat(iv.(string), 64)  // Error ignored
```

**Impact:** Corrupt pricing data could propagate silently through the system.

---

### 3.5 Missing Context Cancellation Handling

**Severity:** Medium
**Location:** `impl/cache/provisioned.go`, `assistant/llm_enhancer.go`

Long-running operations don't respect context cancellation:

```go
// impl/cache/provisioned.go:44-81
func (sl *ProvisionedPrice) InvalidateCache() (response InvalidationStatus) {
    // No context parameter - can't be cancelled
    sl.Regions = make(map[string]price.ProvisionedRegion)
    err := sl.processWarmData()  // May take minutes, uncancellable
    // ...
}
```

**Recommendation:** Add context to long operations:
```go
func (sl *ProvisionedPrice) InvalidateCache(ctx context.Context) (response InvalidationStatus, err error) {
    select {
    case <-ctx.Done():
        return InvalidationStatus{}, ctx.Err()
    default:
    }
    // Continue with operation...
}
```

---

## 4. Low Priority Issues

### 4.1 Inconsistent Logging

**Severity:** Low
**Location:** Various

Mix of structured and unstructured logging:

```go
// Structured (good):
app.logger.Error("failed to unmarshal request", zap.Error(err))

// Unstructured (inconsistent):
fmt.Printf("INFO: Processing OnDemand pricing for region: %s\n", region)
fmt.Println("skipping...", fullUrl)
```

**Recommendation:** Replace all `fmt.Print*` with structured logging.

---

### 4.2 Missing Godoc Comments

**Severity:** Low
**Location:** Various

Several exported functions lack documentation:

```go
// Missing docs on:
// - handler.go: createTimeSeriesRequestWithDefaults
// - handler.go: createSearchRequestWithDefaults
// - handler.go: createVectorRequestWithDefaults
// - handler.go: contains
```

---

### 4.3 Unused Error Returns

**Severity:** Low
**Location:** `impl/cache/provisioned.go:387-388`

```go
// impl/cache/provisioned.go:387-388
data, _ := os.ReadFile("./priceCache.json")
_ = json.Unmarshal(data, &sl.Regions)
```

File read and JSON unmarshal errors are completely ignored.

---

### 4.4 Type Assertion Without Checks

**Severity:** Low
**Location:** `impl/cache/provisioned.go` (multiple)

Unsafe type assertions that could panic:

```go
// impl/cache/provisioned.go:197
rp := esjson.(map[string]interface{})  // Could panic

// impl/cache/provisioned.go:226
itemMap := itemValue.(map[string]interface{})  // Could panic
```

**Safer approach:**
```go
rp, ok := esjson.(map[string]interface{})
if !ok {
    return fmt.Errorf("unexpected response type: %T", esjson)
}
```

---

## 5. Positive Observations

### 5.1 Clean Architecture

The codebase demonstrates excellent separation of concerns:

```
os-calculator-services/
├── main.go              → Entry point only
├── routes.go            → HTTP routing
├── handler.go           → HTTP handlers
├── middleware.go        → Cross-cutting concerns
├── impl/                → Business logic
│   ├── provisioned/     → Managed cluster calculations
│   ├── serverless/      → Serverless calculations
│   ├── price/           → Pricing logic
│   ├── cache/           → Cache management
│   ├── instances/       → Instance definitions
│   ├── regions/         → Region mapping
│   └── commons/         → Shared utilities
├── assistant/           → AI/NLP features
├── mcp/                 → Model Context Protocol
└── scheduler/           → Background tasks
```

### 5.2 No Circular Dependencies

Verified dependency flow is strictly downward:
- `main` → `routes` → `handler` → `impl/*` → utilities
- No cycles detected

### 5.3 Good Rate Limiting Implementation

The token bucket rate limiter in `middleware.go` is well-implemented:

```go
type RateLimiter struct {
    mu       sync.RWMutex
    buckets  map[string]*tokenBucket
    rate     int
    interval time.Duration
    burst    int
}
```

Features:
- Thread-safe with proper mutex usage
- Automatic cleanup of stale entries
- Configurable via environment variables
- Separate limiters for different operation types

### 5.4 Proper CORS Implementation

Origin validation is security-conscious:

```go
func isOriginAllowed(origin string) bool {
    if allowedOrigins[origin] {
        return true
    }
    for _, suffix := range allowedOriginSuffixes {
        if strings.HasSuffix(origin, suffix) {
            if strings.HasPrefix(origin, "https://") {  // Requires HTTPS!
                return true
            }
        }
    }
    return false
}
```

### 5.5 Graceful Degradation

The assistant handler gracefully degrades when LLM is unavailable:

```go
// assistant/handler.go:29-37
enhancer, err := NewLLMEnhancer(context.Background(), logger)
if err != nil {
    if logger != nil {
        logger.Warn("Failed to initialize LLM enhancer, will use NLP-only parsing", zap.Error(err))
    }
    enhancer = nil  // Service continues without LLM
}
```

---

## 6. Recommendations Summary

### Immediate Action Required

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| Critical | Fix silent error handling in handlers | Low | High |
| Critical | Handle JSON marshal errors | Low | High |
| High | Extract duplicate code to shared packages | Medium | Medium |

### Short-term Improvements

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| High | Refactor global state to dependency injection | High | High |
| Medium | Add request validation | Medium | Medium |
| Medium | Centralize configuration | Medium | Medium |
| Medium | Add context cancellation support | Medium | Medium |

### Long-term Technical Debt

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| Medium | Handle strconv parse errors | Low | Low |
| Low | Standardize logging | Low | Low |
| Low | Add missing Godoc comments | Low | Low |
| Low | Add safe type assertions | Low | Low |

---

## Appendix A: File-by-File Issues

| File | Critical | High | Medium | Low |
|------|----------|------|--------|-----|
| handler.go | 2 | 0 | 2 | 1 |
| middleware.go | 0 | 1 | 0 | 0 |
| impl/cache/provisioned.go | 0 | 0 | 3 | 3 |
| impl/provisioned/handler.go | 0 | 1 | 0 | 0 |
| impl/serverless/handler.go | 0 | 1 | 0 | 0 |
| mcp/server.go | 0 | 2 | 0 | 0 |
| assistant/handler.go | 0 | 0 | 1 | 0 |
| assistant/llm_enhancer.go | 0 | 0 | 1 | 0 |

---

## Appendix B: Dependency Graph

```
main.go
  └── routes.go
        ├── handler.go
        │     ├── impl/provisioned/
        │     │     ├── impl/price/
        │     │     ├── impl/instances/
        │     │     ├── impl/regions/
        │     │     └── impl/cache/
        │     ├── impl/serverless/
        │     │     ├── impl/price/
        │     │     ├── impl/cache/
        │     │     ├── impl/regions/
        │     │     └── impl/commons/
        │     └── assistant/
        │           └── mcp/ (client)
        └── middleware.go
  └── mcp/server.go
        ├── impl/provisioned/
        └── impl/serverless/
  └── scheduler/
        └── HTTP client (internal)
```

No circular dependencies present.

---

*Report generated by Senior Go Developer code review analysis*
