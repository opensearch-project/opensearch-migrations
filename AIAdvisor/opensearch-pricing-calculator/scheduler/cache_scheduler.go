// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package scheduler

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sync"
	"time"

	"go.uber.org/zap"
)

// CacheScheduler handles nightly cache invalidation
type CacheScheduler struct {
	logger                 *zap.Logger
	baseURL                string
	ticker                 *time.Ticker
	done                   chan bool
	mu                     sync.RWMutex
	isRunning              bool
	invalidationInProgress bool
	lastRun                time.Time
	nextRun                time.Time
	httpClient             *http.Client
}

// Status represents the status of the scheduler for logging
type Status struct {
	Timestamp         time.Time               `json:"timestamp"`
	SchedulerStatus   string                  `json:"scheduler_status"`
	LastRun           time.Time               `json:"last_run,omitempty"`
	NextRun           time.Time               `json:"next_run"`
	CacheInvalidation CacheInvalidationResult `json:"cache_invalidation"`
	Error             string                  `json:"error,omitempty"`
}

// CacheInvalidationResult represents the result of cache invalidation
type CacheInvalidationResult struct {
	Success    bool      `json:"success"`
	Timestamp  time.Time `json:"timestamp"`
	Duration   string    `json:"duration"`
	Endpoint   string    `json:"endpoint"`
	StatusCode int       `json:"status_code,omitempty"`
	Error      string    `json:"error,omitempty"`
}

// NewCacheScheduler creates a new cache scheduler
func NewCacheScheduler(logger *zap.Logger, baseURL string) *CacheScheduler {
	if baseURL == "" {
		baseURL = "http://localhost:5050"
	}

	return &CacheScheduler{
		logger:  logger.Named("cache_scheduler"),
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 30 * time.Minute,
		},
		done: make(chan bool),
	}
}

// Start begins the nightly cache invalidation schedule
func (cs *CacheScheduler) Start(ctx context.Context) error {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	if cs.isRunning {
		return fmt.Errorf("scheduler is already running")
	}

	// Calculate next midnight
	now := time.Now()
	nextMidnight := time.Date(now.Year(), now.Month(), now.Day()+1, 0, 0, 0, 0, now.Location())
	cs.nextRun = nextMidnight

	// Calculate duration until next midnight
	durationUntilMidnight := time.Until(nextMidnight)

	cs.logger.Info("starting cache scheduler",
		zap.Time("current_time", now),
		zap.Time("next_run", nextMidnight),
		zap.Duration("time_until_next_run", durationUntilMidnight))

	// Log initial status
	cs.logSchedulerStatus("started", CacheInvalidationResult{}, "")

	cs.isRunning = true

	// Start the scheduler in a goroutine
	go cs.run(ctx, durationUntilMidnight)

	return nil
}

// Stop stops the cache scheduler
func (cs *CacheScheduler) Stop() {
	cs.mu.Lock()

	if !cs.isRunning {
		cs.mu.Unlock()
		return
	}

	cs.logger.Info("stopping cache scheduler")

	// Check if invalidation is in progress
	invalidationInProgress := cs.invalidationInProgress
	if invalidationInProgress {
		cs.logger.Info("cache invalidation in progress, waiting for completion before stopping")
	}
	cs.mu.Unlock()

	// Wait for invalidation to complete if in progress
	if invalidationInProgress {
		for {
			cs.mu.RLock()
			if !cs.invalidationInProgress {
				cs.mu.RUnlock()
				break
			}
			cs.mu.RUnlock()
			time.Sleep(1 * time.Second)
		}
		cs.logger.Info("cache invalidation completed, proceeding with shutdown")
	}

	cs.mu.Lock()
	defer cs.mu.Unlock()

	if cs.ticker != nil {
		cs.ticker.Stop()
	}

	select {
	case cs.done <- true:
	default:
	}

	cs.isRunning = false
	cs.logSchedulerStatus("stopped", CacheInvalidationResult{}, "")
}

// IsRunning returns whether the scheduler is currently running
func (cs *CacheScheduler) IsRunning() bool {
	cs.mu.RLock()
	defer cs.mu.RUnlock()
	return cs.isRunning
}

// GetStatus returns the current status of the scheduler
func (cs *CacheScheduler) GetStatus() Status {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	status := "stopped"
	if cs.isRunning {
		status = "running"
	}

	return Status{
		Timestamp:       time.Now(),
		SchedulerStatus: status,
		LastRun:         cs.lastRun,
		NextRun:         cs.nextRun,
	}
}

// run is the main scheduler loop
func (cs *CacheScheduler) run(ctx context.Context, initialDelay time.Duration) {
	// Wait for the initial delay (until midnight)
	timer := time.NewTimer(initialDelay)
	defer timer.Stop()

	select {
	case <-timer.C:
		// Execute the first run at midnight
		cs.executeInvalidation()
	case <-cs.done:
		cs.logger.Info("scheduler stopped before first execution")
		return
	case <-ctx.Done():
		cs.logger.Info("scheduler stopped due to context cancellation")
		return
	}

	// Create a ticker for daily execution (24 hours)
	cs.ticker = time.NewTicker(24 * time.Hour)
	defer cs.ticker.Stop()

	for {
		select {
		case <-cs.ticker.C:
			cs.executeInvalidation()
		case <-cs.done:
			cs.logger.Info("scheduler stopped")
			return
		case <-ctx.Done():
			cs.logger.Info("scheduler stopped due to context cancellation")
			return
		}
	}
}

// executeInvalidation executes the cache invalidation
func (cs *CacheScheduler) executeInvalidation() {
	start := time.Now()

	cs.logger.Info("executing scheduled cache invalidation")

	// Mark invalidation as in progress
	cs.mu.Lock()
	cs.invalidationInProgress = true
	cs.lastRun = start
	cs.nextRun = start.Add(24 * time.Hour)
	cs.mu.Unlock()

	// Execute the invalidation
	result := cs.invalidateCache()

	// Mark invalidation as complete
	cs.mu.Lock()
	cs.invalidationInProgress = false
	cs.mu.Unlock()

	// Log the result
	if result.Success {
		cs.logSchedulerStatus("cache_invalidation_success", result, "")
	} else {
		cs.logSchedulerStatus("cache_invalidation_failed", result, result.Error)
	}
}

// invalidateCache performs the actual cache invalidation
func (cs *CacheScheduler) invalidateCache() CacheInvalidationResult {
	start := time.Now()
	endpoint := fmt.Sprintf("%s/provisioned/cache/invalidate", cs.baseURL)

	// Create form data
	formData := url.Values{}
	formData.Set("update", "true")

	result := CacheInvalidationResult{
		Timestamp: start,
		Endpoint:  endpoint,
	}

	// Make the POST request
	resp, err := cs.httpClient.PostForm(endpoint, formData)
	if err != nil {
		result.Success = false
		result.Error = fmt.Sprintf("HTTP request failed: %v", err)
		result.Duration = time.Since(start).String()
		return result
	}
	defer func() { _ = resp.Body.Close() }()

	result.StatusCode = resp.StatusCode
	result.Duration = time.Since(start).String()

	if resp.StatusCode != http.StatusOK {
		result.Success = false
		result.Error = fmt.Sprintf("HTTP request returned status %d", resp.StatusCode)
		return result
	}

	result.Success = true
	return result
}

// logSchedulerStatus logs the scheduler status in structured format for OpenSearch alerting
func (cs *CacheScheduler) logSchedulerStatus(status string, cacheResult CacheInvalidationResult, errorMsg string) {
	schedulerStatus := Status{
		Timestamp:         time.Now(),
		SchedulerStatus:   status,
		LastRun:           cs.lastRun,
		NextRun:           cs.nextRun,
		CacheInvalidation: cacheResult,
	}

	if errorMsg != "" {
		schedulerStatus.Error = errorMsg
	}

	// Convert to JSON for structured logging
	statusJSON, err := json.Marshal(schedulerStatus)
	if err != nil {
		cs.logger.Error("failed to marshal scheduler status",
			zap.Error(err),
			zap.String("jobStatus", status))
		return
	}

	// Log at appropriate level based on status
	switch status {
	case "cache_invalidation_failed":
		cs.logger.Error("cache scheduler status",
			zap.String("jobStatus", status),
			zap.String("structured_status", string(statusJSON)),
			zap.Time("timestamp", schedulerStatus.Timestamp),
			zap.String("error", errorMsg))
	case "started", "stopped":
		cs.logger.Info("cache scheduler status",
			zap.String("jobStatus", status),
			zap.String("structured_status", string(statusJSON)),
			zap.Time("timestamp", schedulerStatus.Timestamp))
	default:
		cs.logger.Info("cache scheduler status",
			zap.String("jobStatus", status),
			zap.String("structured_status", string(statusJSON)),
			zap.Time("timestamp", schedulerStatus.Timestamp),
			zap.Time("next_run", schedulerStatus.NextRun),
			zap.Bool("cache_success", cacheResult.Success),
			zap.String("cache_duration", cacheResult.Duration))
	}
}
