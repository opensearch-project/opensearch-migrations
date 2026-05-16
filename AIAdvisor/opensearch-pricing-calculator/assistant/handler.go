// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-pricing-calculator/mcp"
	"go.uber.org/zap"
)

// Handler handles HTTP requests for the assistant API
type Handler struct {
	parser            *NLPParser
	enhancer          *LLMEnhancer
	toolExecutor      *mcp.ToolExecutor
	formatter         *ResponseFormatter
	cache             *AssistantCache
	conversationCache *ConversationCache // Phase 1: Conversation support
	smartRouter       *SmartRouter       // Phase 2: Smart routing
	llmCache          *LLMCache          // Phase 3: LLM response caching
	logger            *zap.Logger
}

// NewHandler creates a new assistant handler
func NewHandler(logger *zap.Logger, te *mcp.ToolExecutor) (*Handler, error) {
	enhancer, err := NewLLMEnhancer(context.Background(), logger)
	if err != nil {
		// Log warning but continue - we can work without LLM enhancement
		if logger != nil {
			logger.Warn("Failed to initialize LLM enhancer, will use NLP-only parsing", zap.Error(err))
		}
		enhancer = nil
	}

	return &Handler{
		parser:            NewNLPParser(),
		enhancer:          enhancer,
		toolExecutor:      te,
		formatter:         NewResponseFormatter(),
		cache:             NewAssistantCache(5 * time.Minute),
		conversationCache: NewConversationCache(15 * time.Minute), // Phase 1: 15-minute session TTL
		smartRouter:       NewSmartRouter(),                       // Phase 2: Smart routing
		llmCache:          NewLLMCache(30 * time.Minute),          // Phase 3: 30-minute LLM cache TTL
		logger:            logger,
	}, nil
}

// HandleEstimate handles POST /api/assistant/estimate
func (h *Handler) HandleEstimate(w http.ResponseWriter, r *http.Request) {
	startTime := time.Now()
	ctx := r.Context()

	// Parse request
	var req EstimateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.logger.Error("Failed to decode request", zap.Error(err))
		h.writeError(w, "Invalid request format", http.StatusBadRequest)
		return
	}

	if req.Query == "" {
		h.writeError(w, "Query is required", http.StatusBadRequest)
		return
	}

	h.logger.Info("Processing assistant query", zap.String("query", req.Query))

	// Phase 1 & 2: Get or create conversation session
	sessionID := r.Header.Get("X-Session-ID")
	session := h.conversationCache.GetOrCreate(sessionID)

	h.logger.Info("Conversation session",
		zap.String("sessionID", session.ID),
		zap.Int("messageCount", len(session.Messages)),
		zap.Bool("isNewSession", sessionID == "" || sessionID != session.ID))

	// Check cache first
	if cached, found := h.cache.Get(req.Query); found {
		h.logger.Info("Returning cached response", zap.String("query", req.Query))
		cached = h.formatter.FormatCachedResponse(cached)
		// Include session ID in cached response
		w.Header().Set("X-Session-ID", session.ID)
		h.writeJSON(w, cached, http.StatusOK)
		return
	}

	// Parse query with NLP
	parsedQuery, err := h.parser.Parse(req.Query)
	if err != nil {
		h.logger.Error("Failed to parse query", zap.Error(err))
		response := h.formatter.FormatParsingError(req.Query, 0)
		// Always return session ID, even for errors
		w.Header().Set("X-Session-ID", session.ID)
		h.writeJSON(w, response, http.StatusOK)
		return
	}

	// Phase 2: Smart routing decision - check BEFORE any validation
	parsedWithLLM := false
	var managedResult, serverlessResult map[string]interface{}
	var mcpErr error

	// Use smart router to decide whether to use LLM
	// For conversational queries, always use LLM (it will have context from history)
	useLLM := h.enhancer != nil && h.smartRouter.ShouldUseLLM(req.Query, session, parsedQuery.Confidence)

	// Feature flag: Force LLM path for 100% of queries (overrides SmartRouter)
	// Set FORCE_LLM_PATH=true to enable
	if forceLLM := os.Getenv("FORCE_LLM_PATH"); forceLLM == "true" && h.enhancer != nil {
		useLLM = true
		h.logger.Info("Feature flag: Forcing LLM path for all queries",
			zap.String("FORCE_LLM_PATH", forceLLM))
	}

	// IMPORTANT: Skip ALL validation if we're in an active conversation session
	// If the session has message history, the LLM has full context and can handle any query
	// This allows natural follow-ups like "what about 20M vectors?" without explicit OpenSearch keywords
	hasConversationHistory := len(session.Messages) > 0

	// Skip ALL validation for active conversations or when using LLM - let LLM handle context
	if !useLLM && !hasConversationHistory {
		// Only validate for first-time queries (no conversation history) using NLP path

		// Validate minimum requirements (workload type)
		if parsedQuery.WorkloadType == "" {
			response := h.formatter.FormatParsingError(req.Query, parsedQuery.Confidence)
			// Always return session ID, even for errors
			w.Header().Set("X-Session-ID", session.ID)
			h.writeJSON(w, response, http.StatusOK)
			return
		}

		// Validate required parameters
		hasAllParams, missingParams, followUpQuestion := ValidateRequiredParameters(parsedQuery)
		if !hasAllParams {
			h.logger.Info("Missing required parameters",
				zap.String("workloadType", parsedQuery.WorkloadType),
				zap.Strings("missingParams", missingParams))

			// Return follow-up question response
			response := &EstimateResponse{
				Success:          false,
				NeedsMoreInfo:    true,
				FollowUpQuestion: followUpQuestion,
				WorkloadType:     parsedQuery.WorkloadType,
				ParsedQuery:      parsedQuery,
				Metadata: &Metadata{
					Confidence:       parsedQuery.Confidence,
					ParsedWithLLM:    false,
					ProcessingTimeMs: time.Since(startTime).Milliseconds(),
					CachedResponse:   false,
				},
			}
			parsedQuery.MissingParameters = missingParams
			// Always return session ID, even for validation failures
			w.Header().Set("X-Session-ID", session.ID)
			h.writeJSON(w, response, http.StatusOK)
			return
		}
	}

	// Force LLM usage if we have conversation history (overrides SmartRouter decision)
	if hasConversationHistory && h.enhancer != nil {
		useLLM = true
		h.logger.Info("Forcing LLM path due to active conversation",
			zap.Int("messageCount", len(session.Messages)))
	}

	if useLLM {
		h.logger.Info("Smart router: Using LLM path",
			zap.Float64("nlpConfidence", parsedQuery.Confidence),
			zap.Int("sessionMessages", len(session.Messages)),
			zap.Bool("isConversational", h.smartRouter.IsConversational(req.Query)))

		// Get conversation context if needed
		var history []ConversationMessage
		if h.smartRouter.ShouldUseConversationContext(session) {
			count := h.smartRouter.GetRecentMessageCount(session)
			history = h.conversationCache.GetRecentMessages(session.ID, count)
			h.logger.Info("Including conversation history",
				zap.Int("messageCount", len(history)))
		}

		// Phase 3: Check LLM cache before making expensive LLM call
		var enhancedResponse *EnhancedLLMResponse
		var enhanceErr error
		var usedLLMCache bool

		if cachedResponse, found := h.llmCache.Get(req.Query, history); found {
			h.logger.Info("LLM cache hit - using cached response",
				zap.String("query", req.Query),
				zap.Int("contextMessages", len(history)))
			enhancedResponse = cachedResponse
			usedLLMCache = true
		} else {
			// LLM cache miss - call Bedrock
			h.logger.Info("LLM cache miss - calling Bedrock",
				zap.String("query", req.Query))
			enhancedResponse, enhanceErr = h.enhancer.EnhanceWithContext(ctx, req.Query, history)

			// Store successful LLM response in cache
			if enhanceErr == nil && enhancedResponse != nil {
				h.llmCache.Set(req.Query, history, enhancedResponse)
				h.logger.Info("Stored LLM response in cache")
			}
		}

		if enhanceErr != nil {
			h.logger.Warn("LLM enhancement failed, using NLP parse", zap.Error(enhanceErr))
			// Fallback to NLP path: Use NLP-parsed query with default MCP arguments
			managedResult, serverlessResult, mcpErr = h.getEstimates(ctx, parsedQuery)
		} else if enhancedResponse.Clarification != "" {
			// LLM returned a clarification/explanation, not a new estimation
			h.logger.Info("LLM returned clarification response",
				zap.String("query", req.Query))

			_ = h.conversationCache.AddMessage(session.ID, "user", req.Query)
			_ = h.conversationCache.AddMessage(session.ID, "assistant", enhancedResponse.Clarification)

			response := &EstimateResponse{
				Success:          false,
				NeedsMoreInfo:    true,
				FollowUpQuestion: enhancedResponse.Clarification,
			}
			w.Header().Set("X-Session-ID", session.ID)
			h.writeJSON(w, response, http.StatusOK)
			return
		} else {
			// Success: Use LLM-generated complete MCP requests
			parsedWithLLM = true
			parsedQuery.Confidence = enhancedResponse.Confidence
			parsedQuery.Intent = enhancedResponse.EnvironmentIntent
			parsedQuery.WorkloadType = enhancedResponse.WorkloadType
			parsedQuery.DeploymentPreference = enhancedResponse.DeploymentPreference

			// Extract parameters from MCP requests to update parsedQuery
			if enhancedResponse.ManagedRequest != nil {
				h.enhancer.extractParametersFromManagedRequest(enhancedResponse.ManagedRequest, parsedQuery)
			}

			cacheStatus := "generated"
			if usedLLMCache {
				cacheStatus = "cached"
			}

			h.logger.Info("LLM generated complete MCP requests with context",
				zap.String("workloadType", enhancedResponse.WorkloadType),
				zap.String("environmentIntent", enhancedResponse.EnvironmentIntent),
				zap.String("deploymentPreference", enhancedResponse.DeploymentPreference),
				zap.String("llmCacheStatus", cacheStatus))

			// Call tool executor with LLM-generated complete requests
			managedResult, serverlessResult, mcpErr = h.getEstimatesFromEnhanced(ctx, enhancedResponse)
		}
	} else {
		// High confidence NLP path: Use NLP-parsed query with default MCP arguments
		h.logger.Info("Smart router: Using NLP fast path",
			zap.Float64("nlpConfidence", parsedQuery.Confidence))
		managedResult, serverlessResult, mcpErr = h.getEstimates(ctx, parsedQuery)
	}
	if mcpErr != nil {
		h.logger.Error("Failed to get MCP estimates", zap.Error(mcpErr))
		response := h.formatter.FormatError(mcpErr,
			"Failed to retrieve cost estimates. Please try again or contact support.")
		// Always return session ID, even for errors
		w.Header().Set("X-Session-ID", session.ID)
		h.writeJSON(w, response, http.StatusOK)
		return
	}

	// Format response
	processingTime := time.Since(startTime).Milliseconds()
	metadata := &Metadata{
		ParsedWithLLM:    parsedWithLLM,
		Confidence:       parsedQuery.Confidence,
		ProcessingTimeMs: processingTime,
		CachedResponse:   false,
	}

	response := h.formatter.Format(parsedQuery, managedResult, serverlessResult, metadata)

	// Cache the response
	h.cache.Set(req.Query, response)

	// Phase 1: Add messages to conversation
	_ = h.conversationCache.AddMessage(session.ID, "user", req.Query)

	// Create detailed summary for assistant message (include parameters for context)
	var assistantMessage string
	paramStr := buildParameterSummary(parsedQuery)

	cs := currencySymbolForRegion(parsedQuery.Region)
	if response.Managed != nil && response.Serverless != nil {
		managedCost := extractCost(response.Managed)
		serverlessCost := extractCost(response.Serverless)
		assistantMessage = fmt.Sprintf("Estimated %s workload with %s: Managed %s%.2f/mo, Serverless %s%.2f/mo",
			parsedQuery.WorkloadType, paramStr, cs, managedCost, cs, serverlessCost)
	} else if response.Managed != nil {
		managedCost := extractCost(response.Managed)
		assistantMessage = fmt.Sprintf("Estimated %s workload with %s (Managed only): %s%.2f/mo",
			parsedQuery.WorkloadType, paramStr, cs, managedCost)
	} else if response.Serverless != nil {
		serverlessCost := extractCost(response.Serverless)
		assistantMessage = fmt.Sprintf("Estimated %s workload with %s (Serverless only): %s%.2f/mo",
			parsedQuery.WorkloadType, paramStr, cs, serverlessCost)
	} else {
		assistantMessage = fmt.Sprintf("Processed %s workload with %s", parsedQuery.WorkloadType, paramStr)
	}
	_ = h.conversationCache.AddMessage(session.ID, "assistant", assistantMessage)

	// Log success
	h.logger.Info("Successfully processed query",
		zap.String("query", req.Query),
		zap.String("sessionID", session.ID),
		zap.String("workloadType", parsedQuery.WorkloadType),
		zap.Float64("confidence", parsedQuery.Confidence),
		zap.Int64("processingTimeMs", processingTime),
		zap.Bool("parsedWithLLM", parsedWithLLM),
		zap.Bool("usedSmartRouter", true),
	)

	// Include session ID in response
	w.Header().Set("X-Session-ID", session.ID)
	h.writeJSON(w, response, http.StatusOK)
}

// HandleClearCache handles POST /api/assistant/clearCache
func (h *Handler) HandleClearCache(w http.ResponseWriter, r *http.Request) {
	h.cache.Clear()
	h.logger.Info("Assistant cache cleared")

	response := map[string]interface{}{
		"success": true,
		"message": "Cache cleared successfully",
	}
	h.writeJSON(w, response, http.StatusOK)
}

// HandleCacheStats handles GET /api/assistant/cache/stats
func (h *Handler) HandleCacheStats(w http.ResponseWriter, r *http.Request) {
	stats := map[string]interface{}{
		"assistantCache": map[string]interface{}{
			"size": h.cache.Size(),
			"ttl":  "5 minutes",
		},
		"llmCache": h.llmCache.GetStats(),
	}
	h.writeJSON(w, stats, http.StatusOK)
}

// HandleClearLLMCache handles POST /api/assistant/clearLLMCache
func (h *Handler) HandleClearLLMCache(w http.ResponseWriter, r *http.Request) {
	h.llmCache.Clear()
	h.logger.Info("LLM cache cleared")

	response := map[string]interface{}{
		"success": true,
		"message": "LLM cache cleared successfully",
	}
	h.writeJSON(w, response, http.StatusOK)
}

// HandleLLMCacheStats handles GET /api/assistant/llm-cache/stats
func (h *Handler) HandleLLMCacheStats(w http.ResponseWriter, r *http.Request) {
	stats := h.llmCache.GetStats()
	h.writeJSON(w, stats, http.StatusOK)
}

// writeJSON writes a JSON response
func (h *Handler) writeJSON(w http.ResponseWriter, data interface{}, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)

	if err := json.NewEncoder(w).Encode(data); err != nil {
		h.logger.Error("Failed to encode JSON response", zap.Error(err))
	}
}

// writeError writes an error response
func (h *Handler) writeError(w http.ResponseWriter, message string, status int) {
	response := h.formatter.FormatError(
		&HTTPError{Message: message, StatusCode: status},
		"",
	)
	h.writeJSON(w, response, status)
}

// HTTPError represents an HTTP error
type HTTPError struct {
	Message    string
	StatusCode int
}

func (e *HTTPError) Error() string {
	return e.Message
}

// extractCost extracts the totalMonthlyCost from a map response
// currencySymbolForRegion returns the display symbol for a region's currency.
func currencySymbolForRegion(region string) string {
	if strings.HasPrefix(region, "cn-") {
		return "¥"
	}
	return "$"
}

func extractCost(estimate map[string]interface{}) float64 {
	if cost, ok := estimate["totalMonthlyCost"].(float64); ok {
		return cost
	}
	return 0.0
}

// buildParameterSummary creates a concise summary of query parameters for conversation context
func buildParameterSummary(pq *ParsedQuery) string {
	var parts []string

	// Size - be explicit about timeseries daily volume
	if pq.Size > 0 {
		if pq.WorkloadType == "timeSeries" || pq.WorkloadType == "timeseries" {
			parts = append(parts, fmt.Sprintf("%dGB/day", pq.Size))
		} else {
			parts = append(parts, fmt.Sprintf("%dGB total", pq.Size))
		}
	}

	// Vector-specific
	if pq.VectorCount > 0 {
		parts = append(parts, fmt.Sprintf("%d vectors", pq.VectorCount))
	}
	if pq.Dimensions > 0 {
		parts = append(parts, fmt.Sprintf("%d dimensions", pq.Dimensions))
	}
	if pq.VectorEngine != "" {
		parts = append(parts, fmt.Sprintf("engine=%s", pq.VectorEngine))
	}

	// Retention periods
	if pq.HotPeriod > 0 {
		parts = append(parts, fmt.Sprintf("%d days hot retention", pq.HotPeriod))
	}
	if pq.WarmPeriod > 0 {
		parts = append(parts, fmt.Sprintf("%d days warm retention", pq.WarmPeriod))
	}

	// Region
	if pq.Region != "" && pq.Region != "us-east-1" {
		parts = append(parts, fmt.Sprintf("region=%s", pq.Region))
	}

	// Environment intent
	if pq.Intent != "" {
		parts = append(parts, fmt.Sprintf("intent=%s", pq.Intent))
	}

	if len(parts) == 0 {
		return "default parameters"
	}

	return strings.Join(parts, ", ")
}

// getEstimates calls ToolExecutor directly to get both managed and serverless estimates
// from NLP-parsed query parameters.
func (h *Handler) getEstimates(ctx context.Context, parsedQuery *ParsedQuery) (map[string]interface{}, map[string]interface{}, error) {
	var managedResult, serverlessResult map[string]interface{}
	var managedErr, serverlessErr error

	// Call both in parallel
	done := make(chan bool, 2)

	go func() {
		args := buildProvisionedArgs(parsedQuery)
		resp, err := h.toolExecutor.ExecuteProvisioned(args)
		if err != nil {
			managedErr = err
		} else {
			managedResult, managedErr = provisionedResponseToMap(resp)
		}
		done <- true
	}()

	go func() {
		args := buildServerlessArgs(parsedQuery)
		serverlessResult, serverlessErr = h.toolExecutor.ExecuteServerless(ctx, args)
		done <- true
	}()

	<-done
	<-done

	// Return error only if both failed
	if managedErr != nil && serverlessErr != nil {
		return nil, nil, fmt.Errorf("both estimates failed: managed=%v, serverless=%v", managedErr, serverlessErr)
	}

	return managedResult, serverlessResult, nil
}

// getEstimatesFromEnhanced calls ToolExecutor directly with LLM-generated request payloads.
func (h *Handler) getEstimatesFromEnhanced(ctx context.Context, enhanced *EnhancedLLMResponse) (map[string]interface{}, map[string]interface{}, error) {
	var managedResult, serverlessResult map[string]interface{}
	var managedErr, serverlessErr error

	needManaged := enhanced.DeploymentPreference == "managed" || enhanced.DeploymentPreference == "both"
	needServerless := enhanced.DeploymentPreference == "serverless" || enhanced.DeploymentPreference == "both"

	done := make(chan bool, 2)

	if needManaged && len(enhanced.ManagedRequest) > 0 {
		go func() {
			enhanced.ManagedRequest["maxConfigurations"] = 3
			resp, err := h.toolExecutor.ExecuteProvisioned(enhanced.ManagedRequest)
			if err != nil {
				managedErr = err
			} else {
				managedResult, managedErr = provisionedResponseToMap(resp)
			}
			done <- true
		}()
	} else {
		go func() { done <- true }()
	}

	if needServerless && len(enhanced.ServerlessRequest) > 0 {
		go func() {
			serverlessResult, serverlessErr = h.toolExecutor.ExecuteServerless(ctx, enhanced.ServerlessRequest)
			done <- true
		}()
	} else {
		go func() { done <- true }()
	}

	<-done
	<-done

	// Return error only if both failed
	if managedErr != nil && serverlessErr != nil {
		return nil, nil, fmt.Errorf("both estimates failed: managed=%v, serverless=%v", managedErr, serverlessErr)
	}

	return managedResult, serverlessResult, nil
}

// provisionedResponseToMap converts a provisioned.EstimateResponse to map[string]interface{}
// via JSON marshal/unmarshal round-trip.
func provisionedResponseToMap(resp interface{}) (map[string]interface{}, error) {
	data, err := json.Marshal(resp)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal provisioned response: %w", err)
	}
	var result map[string]interface{}
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, fmt.Errorf("failed to unmarshal provisioned response to map: %w", err)
	}
	return result, nil
}

// buildProvisionedArgs converts a ParsedQuery to provisioned MCP tool arguments.
func buildProvisionedArgs(params *ParsedQuery) map[string]interface{} {
	// MCP expects arguments nested under workload type (search, vector, or timeSeries)
	workloadConfig := make(map[string]interface{})

	// Common parameters
	workloadConfig["region"] = params.Region
	if params.Size > 0 {
		workloadConfig["size"] = params.Size
	}

	// Workload-specific parameters
	switch params.WorkloadType {
	case "vector":
		if params.VectorCount > 0 {
			workloadConfig["vectorCount"] = params.VectorCount
		}
		if params.Dimensions > 0 {
			workloadConfig["dimensionsCount"] = params.Dimensions
		}
		if params.VectorEngine != "" {
			workloadConfig["vectorEngineType"] = params.VectorEngine
		}
		// Add on-disk compression parameters if specified
		if params.OnDisk {
			workloadConfig["onDisk"] = params.OnDisk
			if params.CompressionLevel > 0 {
				workloadConfig["compressionLevel"] = params.CompressionLevel
			}
		}
		// Add storage compression parameters if specified (for non-vector data)
		if params.DerivedSource {
			workloadConfig["derivedSource"] = params.DerivedSource
		}
		if params.ZstdCompression {
			workloadConfig["zstdCompression"] = params.ZstdCompression
		}
		// Add warm/cold tier parameters for vectors
		if params.WarmPercentage > 0 {
			workloadConfig["warmPercentage"] = params.WarmPercentage
		}
		if params.ColdPercentage > 0 {
			workloadConfig["coldPercentage"] = params.ColdPercentage
		}
		if params.WarmInstanceType != "" {
			workloadConfig["warmInstanceType"] = params.WarmInstanceType
		}
		if params.AutoSelectWarmInstance != nil {
			workloadConfig["autoSelectWarmInstance"] = *params.AutoSelectWarmInstance
		}

	case "timeseries":
		// Map to timeSeries (camelCase as expected by schema)
		// Set defaults if not specified - typical log analytics workload
		hotPeriod := params.HotPeriod
		if hotPeriod == 0 {
			// Default: 7 days in hot storage for typical log analytics
			hotPeriod = 7
		}
		workloadConfig["hotRetentionPeriod"] = hotPeriod

		// Only set warm period if explicitly specified
		if params.WarmPeriod > 0 {
			workloadConfig["warmRetentionPeriod"] = params.WarmPeriod
		}

		// Only set cold period if explicitly specified
		if params.ColdPeriod > 0 {
			workloadConfig["coldRetentionPeriod"] = params.ColdPeriod
		}

		// Add warm instance selection parameters
		if params.WarmInstanceType != "" {
			workloadConfig["warmInstanceType"] = params.WarmInstanceType
		}
		if params.AutoSelectWarmInstance != nil {
			workloadConfig["autoSelectWarmInstance"] = *params.AutoSelectWarmInstance
		}

		// Add storage compression parameters if specified
		if params.DerivedSource {
			workloadConfig["derivedSource"] = params.DerivedSource
		}
		if params.ZstdCompression {
			workloadConfig["zstdCompression"] = params.ZstdCompression
		}

	case "search":
		// Add storage compression parameters if specified
		if params.DerivedSource {
			workloadConfig["derivedSource"] = params.DerivedSource
		}
		if params.ZstdCompression {
			workloadConfig["zstdCompression"] = params.ZstdCompression
		}
		// Add warm/cold tier parameters for search
		if params.WarmPercentage > 0 {
			workloadConfig["warmPercentage"] = params.WarmPercentage
		}
		if params.ColdPercentage > 0 {
			workloadConfig["coldPercentage"] = params.ColdPercentage
		}
		if params.WarmInstanceType != "" {
			workloadConfig["warmInstanceType"] = params.WarmInstanceType
		}
		if params.AutoSelectWarmInstance != nil {
			workloadConfig["autoSelectWarmInstance"] = *params.AutoSelectWarmInstance
		}
	}

	// Add Multi-AZ with Standby parameter if specified (applies to all workload types)
	if params.MultiAzWithStandby {
		workloadConfig["multiAzWithStandby"] = params.MultiAzWithStandby
	}

	// Return with workload type as key
	workloadKey := params.WorkloadType
	if workloadKey == "timeseries" {
		workloadKey = "timeSeries" // Convert to camelCase
	}

	return map[string]interface{}{
		workloadKey:         workloadConfig,
		"maxConfigurations": 3,
	}
}

// buildServerlessArgs converts a ParsedQuery to serverless MCP tool arguments.
func buildServerlessArgs(params *ParsedQuery) map[string]interface{} {
	// Serverless requires ingest (mandatory) + optional workload configs
	args := map[string]interface{}{
		"region": params.Region,
		"ingest": map[string]interface{}{
			// Default minimal ingest configuration
			"minIndexingRate": 0.1,
			"maxIndexingRate": 1.0,
			"timePerDayAtMax": 8,
		},
	}

	// Add workload-specific configuration based on type
	switch params.WorkloadType {
	case "vector":
		vectorConfig := make(map[string]interface{})
		if params.Size > 0 {
			vectorConfig["size"] = params.Size
		}
		if params.VectorCount > 0 {
			vectorConfig["vectorCount"] = params.VectorCount
		}
		if params.Dimensions > 0 {
			vectorConfig["dimensionsCount"] = params.Dimensions
		}
		if params.VectorEngine != "" {
			vectorConfig["vectorEngineType"] = params.VectorEngine
		}
		// Add on-disk compression parameters if specified
		if params.OnDisk {
			vectorConfig["onDisk"] = params.OnDisk
			if params.CompressionLevel > 0 {
				vectorConfig["compressionLevel"] = params.CompressionLevel
			}
		}
		// Add storage compression parameters if specified (for non-vector data)
		if params.DerivedSource {
			vectorConfig["derivedSource"] = params.DerivedSource
		}
		if params.ZstdCompression {
			vectorConfig["zstdCompression"] = params.ZstdCompression
		}
		args["vector"] = vectorConfig

	case "search":
		searchConfig := make(map[string]interface{})
		if params.Size > 0 {
			searchConfig["collectionSize"] = params.Size
		}
		// Add storage compression parameters if specified
		if params.DerivedSource {
			searchConfig["derivedSource"] = params.DerivedSource
		}
		if params.ZstdCompression {
			searchConfig["zstdCompression"] = params.ZstdCompression
		}
		args["search"] = searchConfig

	case "timeseries":
		timeSeriesConfig := make(map[string]interface{})
		if params.Size > 0 {
			timeSeriesConfig["dailyIndexSize"] = params.Size / 30 // Approximate daily from total
		}

		// Set defaults if not specified - typical log analytics workload
		hotPeriod := params.HotPeriod
		if hotPeriod == 0 {
			// Default: 1 day in hot storage for serverless (minimum)
			hotPeriod = 1
		}
		timeSeriesConfig["daysInHot"] = hotPeriod

		// Only set warm period if explicitly specified
		if params.WarmPeriod > 0 {
			timeSeriesConfig["daysInWarm"] = params.WarmPeriod
		}

		// Add storage compression parameters if specified
		if params.DerivedSource {
			timeSeriesConfig["derivedSource"] = params.DerivedSource
		}
		if params.ZstdCompression {
			timeSeriesConfig["zstdCompression"] = params.ZstdCompression
		}

		args["timeSeries"] = timeSeriesConfig
	}

	return args
}
