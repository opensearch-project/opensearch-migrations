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

	"go.uber.org/zap"
)

const headerSessionID = "X-Session-ID"

// Handler handles HTTP requests for the assistant API
type Handler struct {
	parser            *NLPParser
	enhancer          *LLMEnhancer
	mcpClient         *MCPClient
	formatter         *ResponseFormatter
	cache             *AssistantCache
	conversationCache *ConversationCache // Phase 1: Conversation support
	smartRouter       *SmartRouter       // Phase 2: Smart routing
	llmCache          *LLMCache          // Phase 3: LLM response caching
	logger            *zap.Logger
}

// NewHandler creates a new assistant handler
func NewHandler(logger *zap.Logger) (*Handler, error) {
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
		mcpClient:         NewMCPClient(),
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
	sessionID := r.Header.Get(headerSessionID)
	session := h.conversationCache.GetOrCreate(sessionID)

	h.logger.Info("Conversation session",
		zap.String("sessionID", session.ID),
		zap.Int("messageCount", len(session.Messages)),
		zap.Bool("isNewSession", sessionID == "" || sessionID != session.ID))

	// Check cache first
	if cached, found := h.cache.Get(req.Query); found {
		h.logger.Info("Returning cached response", zap.String("query", req.Query))
		cached = h.formatter.FormatCachedResponse(cached)
		w.Header().Set(headerSessionID, session.ID)
		h.writeJSON(w, cached, http.StatusOK)
		return
	}

	// Parse query with NLP
	parsedQuery, err := h.parser.Parse(req.Query)
	if err != nil {
		h.logger.Error("Failed to parse query", zap.Error(err))
		response := h.formatter.FormatParsingError(req.Query, 0)
		w.Header().Set(headerSessionID, session.ID)
		h.writeJSON(w, response, http.StatusOK)
		return
	}

	// Determine whether to use LLM
	useLLM := h.shouldUseLLMPath(req.Query, session, parsedQuery)
	hasConversationHistory := len(session.Messages) > 0

	// Validate NLP-only path for first-time queries
	if !useLLM && !hasConversationHistory {
		if earlyResp := h.validateNLPQuery(parsedQuery, req.Query, startTime); earlyResp != nil {
			w.Header().Set(headerSessionID, session.ID)
			h.writeJSON(w, earlyResp, http.StatusOK)
			return
		}
	}

	// Force LLM usage if we have conversation history
	if hasConversationHistory && h.enhancer != nil {
		useLLM = true
		h.logger.Info("Forcing LLM path due to active conversation",
			zap.Int("messageCount", len(session.Messages)))
	}

	// Get estimates via LLM or NLP path
	managedResult, serverlessResult, parsedWithLLM, mcpErr := h.getEstimates(ctx, useLLM, req.Query, session, parsedQuery)
	if mcpErr != nil {
		h.logger.Error("Failed to get MCP estimates", zap.Error(mcpErr))
		response := h.formatter.FormatError(mcpErr,
			"Failed to retrieve cost estimates. Please try again or contact support.")
		w.Header().Set(headerSessionID, session.ID)
		h.writeJSON(w, response, http.StatusOK)
		return
	}

	// Extract estimate data from MCP results
	managedResult, serverlessResult = h.extractEstimateResults(managedResult, serverlessResult)

	// Format response
	processingTime := time.Since(startTime).Milliseconds()
	metadata := &Metadata{
		ParsedWithLLM:    parsedWithLLM,
		Confidence:       parsedQuery.Confidence,
		ProcessingTimeMs: processingTime,
		CachedResponse:   false,
	}

	response := h.formatter.Format(parsedQuery, managedResult, serverlessResult, metadata)
	h.cache.Set(req.Query, response)

	// Record conversation
	h.recordConversation(session.ID, req.Query, parsedQuery, response)

	h.logger.Info("Successfully processed query",
		zap.String("query", req.Query),
		zap.String("sessionID", session.ID),
		zap.String("workloadType", parsedQuery.WorkloadType),
		zap.Float64("confidence", parsedQuery.Confidence),
		zap.Int64("processingTimeMs", processingTime),
		zap.Bool("parsedWithLLM", parsedWithLLM),
		zap.Bool("usedSmartRouter", true),
	)

	w.Header().Set(headerSessionID, session.ID)
	h.writeJSON(w, response, http.StatusOK)
}

// shouldUseLLMPath determines whether to route the query through the LLM path.
func (h *Handler) shouldUseLLMPath(query string, session *ConversationSession, parsedQuery *ParsedQuery) bool {
	useLLM := h.enhancer != nil && h.smartRouter.ShouldUseLLM(query, session, parsedQuery.Confidence)

	if forceLLM := os.Getenv("FORCE_LLM_PATH"); forceLLM == "true" && h.enhancer != nil {
		useLLM = true
		h.logger.Info("Feature flag: Forcing LLM path for all queries",
			zap.String("FORCE_LLM_PATH", forceLLM))
	}

	return useLLM
}

// validateNLPQuery checks if the NLP-parsed query has all required parameters.
// Returns a response to send early if validation fails, or nil if validation passes.
func (h *Handler) validateNLPQuery(parsedQuery *ParsedQuery, rawQuery string, startTime time.Time) interface{} {
	if parsedQuery.WorkloadType == "" {
		return h.formatter.FormatParsingError(rawQuery, parsedQuery.Confidence)
	}

	hasAllParams, missingParams, followUpQuestion := ValidateRequiredParameters(parsedQuery)
	if !hasAllParams {
		h.logger.Info("Missing required parameters",
			zap.String("workloadType", parsedQuery.WorkloadType),
			zap.Strings("missingParams", missingParams))

		parsedQuery.MissingParameters = missingParams
		return &EstimateResponse{
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
	}

	return nil
}

// getEstimates retrieves cost estimates via either LLM or NLP path.
func (h *Handler) getEstimates(ctx context.Context, useLLM bool, query string, session *ConversationSession, parsedQuery *ParsedQuery) (map[string]interface{}, map[string]interface{}, bool, error) {
	if !useLLM {
		h.logger.Info("Smart router: Using NLP fast path",
			zap.Float64("nlpConfidence", parsedQuery.Confidence))
		managed, serverless, err := h.mcpClient.GetEstimates(ctx, parsedQuery)
		return managed, serverless, false, err
	}

	return h.getEstimatesViaLLM(ctx, query, session, parsedQuery)
}

// getEstimatesViaLLM handles the LLM enhancement path including caching and fallback.
func (h *Handler) getEstimatesViaLLM(ctx context.Context, query string, session *ConversationSession, parsedQuery *ParsedQuery) (map[string]interface{}, map[string]interface{}, bool, error) {
	h.logger.Info("Smart router: Using LLM path",
		zap.Float64("nlpConfidence", parsedQuery.Confidence),
		zap.Int("sessionMessages", len(session.Messages)),
		zap.Bool("isConversational", h.smartRouter.IsConversational(query)))

	history := h.getConversationHistory(session)
	enhancedResponse, usedLLMCache, enhanceErr := h.getLLMResponse(ctx, query, history)

	if enhanceErr != nil {
		h.logger.Warn("LLM enhancement failed, using NLP parse", zap.Error(enhanceErr))
		managed, serverless, err := h.mcpClient.GetEstimates(ctx, parsedQuery)
		return managed, serverless, false, err
	}

	// Update parsedQuery with LLM results
	parsedQuery.Confidence = enhancedResponse.Confidence
	parsedQuery.Intent = enhancedResponse.EnvironmentIntent
	parsedQuery.WorkloadType = enhancedResponse.WorkloadType
	parsedQuery.DeploymentPreference = enhancedResponse.DeploymentPreference

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

	managed, serverless, err := h.mcpClient.GetEstimatesFromEnhanced(ctx, enhancedResponse)
	return managed, serverless, true, err
}

// getConversationHistory retrieves conversation history if the smart router deems it necessary.
func (h *Handler) getConversationHistory(session *ConversationSession) []ConversationMessage {
	if !h.smartRouter.ShouldUseConversationContext(session) {
		return nil
	}
	count := h.smartRouter.GetRecentMessageCount(session)
	history := h.conversationCache.GetRecentMessages(session.ID, count)
	h.logger.Info("Including conversation history",
		zap.Int("messageCount", len(history)))
	return history
}

// getLLMResponse checks the LLM cache and falls back to invoking Bedrock.
func (h *Handler) getLLMResponse(ctx context.Context, query string, history []ConversationMessage) (*EnhancedLLMResponse, bool, error) {
	if cachedResponse, found := h.llmCache.Get(query, history); found {
		h.logger.Info("LLM cache hit - using cached response",
			zap.String("query", query),
			zap.Int("contextMessages", len(history)))
		return cachedResponse, true, nil
	}

	h.logger.Info("LLM cache miss - calling Bedrock",
		zap.String("query", query))
	enhancedResponse, err := h.enhancer.EnhanceWithContext(ctx, query, history)
	if err == nil && enhancedResponse != nil {
		h.llmCache.Set(query, history, enhancedResponse)
		h.logger.Info("Stored LLM response in cache")
	}
	return enhancedResponse, false, err
}

// extractEstimateResults extracts structured data from raw MCP results.
func (h *Handler) extractEstimateResults(managedResult, serverlessResult map[string]interface{}) (map[string]interface{}, map[string]interface{}) {
	if managedResult != nil {
		extracted, err := h.mcpClient.ExtractEstimateData(managedResult)
		if err != nil {
			h.logger.Warn("Failed to extract managed estimate data", zap.Error(err))
		} else {
			managedResult = extracted
		}
	}

	if serverlessResult != nil {
		extracted, err := h.mcpClient.ExtractEstimateData(serverlessResult)
		if err != nil {
			h.logger.Warn("Failed to extract serverless estimate data", zap.Error(err))
		} else {
			serverlessResult = extracted
		}
	}

	return managedResult, serverlessResult
}

// recordConversation stores user and assistant messages in the conversation cache.
func (h *Handler) recordConversation(sessionID string, query string, parsedQuery *ParsedQuery, response *EstimateResponse) {
	_ = h.conversationCache.AddMessage(sessionID, "user", query)
	assistantMessage := buildAssistantMessage(parsedQuery, response)
	_ = h.conversationCache.AddMessage(sessionID, "assistant", assistantMessage)
}

// buildAssistantMessage creates a concise summary of the estimate result for conversation context.
func buildAssistantMessage(parsedQuery *ParsedQuery, response *EstimateResponse) string {
	paramStr := buildParameterSummary(parsedQuery)

	if response.Managed != nil && response.Serverless != nil {
		return fmt.Sprintf("Estimated %s workload with %s: Managed $%.2f/mo, Serverless $%.2f/mo",
			parsedQuery.WorkloadType, paramStr, extractCost(response.Managed), extractCost(response.Serverless))
	}
	if response.Managed != nil {
		return fmt.Sprintf("Estimated %s workload with %s (Managed only): $%.2f/mo",
			parsedQuery.WorkloadType, paramStr, extractCost(response.Managed))
	}
	if response.Serverless != nil {
		return fmt.Sprintf("Estimated %s workload with %s (Serverless only): $%.2f/mo",
			parsedQuery.WorkloadType, paramStr, extractCost(response.Serverless))
	}
	return fmt.Sprintf("Processed %s workload with %s", parsedQuery.WorkloadType, paramStr)
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
