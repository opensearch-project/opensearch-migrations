// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-pricing-calculator/cors"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/gorilla/websocket"
	"go.uber.org/zap"
)

// Server represents the MCP server
type Server struct {
	router              *chi.Mux
	logger              *zap.Logger
	httpClient          *http.Client
	baseURL             string
	provisionedHandler  *provisioned.Handler
	serverlessV2Handler *serverless.Handler
	clientName          string
	protocolVersion     string
}

// NewServer creates a new MCP server
func NewServer(logger *zap.Logger, baseURL string, provisionedHandler *provisioned.Handler, serverlessV2Handler *serverless.Handler) *Server {
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)

	return &Server{
		router:              r,
		logger:              logger,
		httpClient:          &http.Client{},
		baseURL:             baseURL,
		provisionedHandler:  provisionedHandler,
		serverlessV2Handler: serverlessV2Handler,
	}
}

// Upgrader for WebSocket connections with origin validation
var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// Validate origin against allowed list
	CheckOrigin: func(r *http.Request) bool {
		origin := r.Header.Get("Origin")
		// Allow connections without Origin header (same-origin requests)
		if origin == "" {
			return true
		}
		return cors.IsOriginAllowed(origin)
	},
}

// RegisterRoutes sets up the HTTP routes for the MCP server
func (s *Server) RegisterRoutes() {
	s.router.Use(middleware.Recoverer)

	// Add CORS middleware for all endpoints with origin validation
	s.router.Use(func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")

			// Only set CORS headers if the origin is in the allowed list
			if origin != "" && cors.IsOriginAllowed(origin) {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Access-Control-Allow-Credentials", "true")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, Upgrade, Connection")
			}

			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}

			next.ServeHTTP(w, r)
		})
	})

	// Handle both /mcp/v1 and /mcp for compatibility
	s.router.Post("/mcp/v1", s.handleJSONRPCRequest)
	s.router.Post("/mcp", s.handleJSONRPCRequest)

	// Handle root paths - Windsurf may try to connect to these
	s.router.Get("/", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("OpenSearch Calculator MCP Server"))
	})

	// SSE endpoints with all HTTP methods for maximum compatibility
	s.router.HandleFunc("/mcp/v1/sse", s.handleSSE)
	s.router.HandleFunc("/mcp/sse", s.handleSSE)

	// Also add SSE endpoints at the root for Windsurf compatibility
	s.router.HandleFunc("/sse", s.handleSSE)

	// WebSocket endpoints for Windsurf compatibility
	s.router.HandleFunc("/mcp/v1/ws", s.handleWebSocket)
	s.router.HandleFunc("/mcp/ws", s.handleWebSocket)
	s.router.HandleFunc("/ws", s.handleWebSocket)
}

// ServeHTTP implements the http.Handler interface
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.router.ServeHTTP(w, r)
}

// handleSSE handles Server-Sent Events for Windsurf compatibility
func (s *Server) handleSSE(w http.ResponseWriter, r *http.Request) {
	// Log the request details
	s.logger.Info("SSE request received",
		zap.String("method", r.Method),
		zap.String("path", r.URL.Path),
		zap.String("user-agent", r.UserAgent()))

	// Handle preflight OPTIONS request
	if r.Method == http.MethodOptions {
		origin := r.Header.Get("Origin")
		if origin != "" && cors.IsOriginAllowed(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization")
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}

	// Accept any method for SSE to maximize compatibility
	// Windsurf might be using POST or another method

	// Set headers for SSE
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no") // Disable buffering in Nginx

	// Set CORS header only for allowed origins
	origin := r.Header.Get("Origin")
	if origin != "" && cors.IsOriginAllowed(origin) {
		w.Header().Set("Access-Control-Allow-Origin", origin)
		w.Header().Set("Access-Control-Allow-Credentials", "true")
	}

	// Keep the connection open
	flusher, ok := w.(http.Flusher)
	if !ok {
		s.logger.Error("streaming not supported")
		http.Error(w, "Streaming not supported", http.StatusInternalServerError)
		return
	}

	// Send an initial message
	fmt.Fprintf(w, "data: {\"type\": \"connected\", \"serverName\": \"os-calculator\"}\n\n")
	flusher.Flush()

	// Send a heartbeat every 15 seconds to keep the connection alive
	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()

	// Keep the connection open until the client disconnects
	for {
		select {
		case <-r.Context().Done():
			s.logger.Info("SSE client disconnected")
			return
		case <-heartbeat.C:
			// Send heartbeat
			fmt.Fprintf(w, "data: {\"type\": \"heartbeat\", \"serverName\": \"os-calculator\"}\n\n")
			flusher.Flush()
		}
	}
}

// handleWebSocket handles WebSocket connections for Windsurf compatibility
func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	// Log the request details
	s.logger.Info("WebSocket request received",
		zap.String("method", r.Method),
		zap.String("path", r.URL.Path),
		zap.String("user-agent", r.UserAgent()))

	// Upgrade the HTTP connection to a WebSocket connection
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.logger.Error("Failed to upgrade to WebSocket", zap.Error(err))
		return
	}
	defer conn.Close()

	// Send an initial message
	initialMsg := map[string]interface{}{
		"type":       "connected",
		"serverName": "os-calculator",
	}
	initialJSON, _ := json.Marshal(initialMsg)
	if err := conn.WriteMessage(websocket.TextMessage, initialJSON); err != nil {
		s.logger.Error("Failed to write initial message", zap.Error(err))
		return
	}

	// Send a heartbeat every 15 seconds to keep the connection alive
	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()

	// Create a done channel to signal when the connection is closed
	done := make(chan struct{})

	// Start a goroutine to read messages from the WebSocket
	go func() {
		defer close(done)
		for {
			_, _, err := conn.ReadMessage()
			if err != nil {
				s.logger.Info("WebSocket client disconnected", zap.Error(err))
				return
			}
		}
	}()

	// Keep the connection open until the client disconnects
	for {
		select {
		case <-done:
			return
		case <-heartbeat.C:
			// Send heartbeat
			heartbeatMsg := map[string]interface{}{
				"type":       "heartbeat",
				"serverName": "os-calculator",
			}
			heartbeatJSON, _ := json.Marshal(heartbeatMsg)
			if err := conn.WriteMessage(websocket.TextMessage, heartbeatJSON); err != nil {
				s.logger.Error("Failed to write heartbeat message", zap.Error(err))
				return
			}
		}
	}
}

// handleJSONRPCRequest handles all JSON-RPC requests
func (s *Server) handleJSONRPCRequest(w http.ResponseWriter, r *http.Request) {
	var request JSONRPC
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		s.writeJSONRPCError(w, nil, -32700, "Parse error", err)
		return
	}

	// Validate JSON-RPC version
	if request.JSONRPC != "2.0" {
		s.writeJSONRPCError(w, request.ID, -32600, "Invalid Request", fmt.Errorf("invalid JSON-RPC version: %s", request.JSONRPC))
		return
	}

	// Dispatch to the appropriate method handler
	switch request.Method {
	case "initialize":
		s.handleInitialize(w, request)
	case "resources/list":
		s.handleResourcesList(w, request)
	case "resources/read":
		s.handleResourcesRead(w, request)
	case "resources/templates/list":
		s.handleResourcesTemplatesList(w, request)
	case "tools/list":
		s.handleToolsList(w, request)
	case "tools/call":
		s.handleToolsCall(w, request)
	case "notifications/initialized":
		// For notifications (no response needed)
		s.handleNotificationsInitialized(w, request)
	default:
		s.writeJSONRPCError(w, request.ID, -32601, "Method not found", fmt.Errorf("method not found: %s", request.Method))
	}
}

// handleInitialize handles the initialize method
func (s *Server) handleInitialize(w http.ResponseWriter, request JSONRPC) {
	// Extract client info from params
	var clientName, clientVersion, protocolVersion string

	// Access params directly since it's already a map[string]interface{}
	if clientInfo, ok := request.Params["clientInfo"].(map[string]interface{}); ok {
		if name, ok := clientInfo["name"].(string); ok {
			clientName = name
		}
		if version, ok := clientInfo["version"].(string); ok {
			clientVersion = version
		}
	}
	if version, ok := request.Params["protocolVersion"].(string); ok {
		protocolVersion = version
	}

	// Store client info for compatibility decisions
	s.clientName = clientName
	s.protocolVersion = protocolVersion

	s.logger.Info("initialize request received",
		zap.String("clientName", clientName),
		zap.String("clientVersion", clientVersion),
		zap.String("protocolVersion", protocolVersion))

	// Use the client's protocol version or default to "2024-11-05" if not provided
	if protocolVersion == "" {
		protocolVersion = "2024-11-05"
	}

	// Return protocol version matching the client's expected version
	result := map[string]interface{}{
		"protocolVersion": protocolVersion,
		"capabilities": map[string]interface{}{
			"tools":     map[string]interface{}{},
			"resources": map[string]interface{}{},
		},
		"serverInfo": map[string]interface{}{
			"name":    "opensearch-calculator",
			"version": "1.0.0",
		},
	}

	s.writeJSONRPCResponse(w, request.ID, result)
}

// handleResourcesList handles the resources/list method
func (s *Server) handleResourcesList(w http.ResponseWriter, request JSONRPC) {
	// No params needed for resources/list
	// Return an empty list since we only have tools, not resources
	s.logger.Info("resources/list request received - returning empty list")

	result := ResourcesListResult{
		Resources: []Resource{},
	}

	s.writeJSONRPCResponse(w, request.ID, result)
}

// handleResourcesRead handles the resources/read method
func (s *Server) handleResourcesRead(w http.ResponseWriter, request JSONRPC) {
	var params ResourceReadParams
	if err := mapToStruct(request.Params, &params); err != nil {
		s.writeJSONRPCError(w, request.ID, -32602, "Invalid params", err)
		return
	}

	// Check which resource is being requested
	switch params.URI {
	case "provisioned_estimate":
		s.writeJSONRPCResponse(w, request.ID, ProvisionedRequestSchema)
	case "serverless_v2_estimate":
		s.writeJSONRPCResponse(w, request.ID, ServerlessRequestSchema)
	default:
		s.writeJSONRPCError(w, request.ID, -32602, "Invalid params", fmt.Errorf("resource not found: %s", params.URI))
	}
}

// handleToolsList handles the tools/list method
func (s *Server) handleToolsList(w http.ResponseWriter, request JSONRPC) {
	// Define available tools
	tools := []map[string]interface{}{
		{
			"name":        "provisioned_estimate",
			"description": "Calculate cost estimates for provisioned Amazon OpenSearch Service domains. Supports search workloads, vector search with various engines (HNSW, IVF, etc.), and time-series data with hot/warm/cold storage tiers. Returns the top 3 most cost-effective cluster configurations with detailed cost breakdowns including compute, storage, and data transfer costs. Each cluster configuration includes instance types, node counts, pricing models (OnDemand, Reserved Instances), and cost explanations to help you understand the trade-offs between different deployment options.",
			"inputSchema": ProvisionedRequestSchema,
		},
		{
			"name":        "serverless_v2_estimate",
			"description": "Calculate cost estimates for serverless v2 Amazon OpenSearch Service collections. Supports ingestion workloads, search queries, time-series data with retention policies, and vector search. Uses OpenSearch Compute Units (OCUs) for flexible scaling and provides detailed cost breakdowns for ingestion, search, and storage components with explanations of OCU usage patterns and scaling behavior.",
			"inputSchema": ServerlessRequestSchema,
		},
	}

	s.writeJSONRPCResponse(w, request.ID, map[string]interface{}{
		"tools": tools,
	})
}

// handleToolsCall handles the tools/call method
func (s *Server) handleToolsCall(w http.ResponseWriter, request JSONRPC) {
	var params ToolCallParams
	if err := mapToStruct(request.Params, &params); err != nil {
		s.writeJSONRPCError(w, request.ID, -32602, "Invalid params", err)
		return
	}

	// Dispatch to the appropriate tool handler
	var result ToolCallResult
	switch params.Name {
	case "provisioned_estimate":
		result = s.handleProvisionedEstimate(params.Arguments)
	case "serverless_v2_estimate":
		result = s.handleServerlessV2Estimate(params.Arguments)
	default:
		s.writeJSONRPCError(w, request.ID, -32602, "Invalid params", fmt.Errorf("tool not found: %s", params.Name))
		return
	}

	s.writeJSONRPCResponse(w, request.ID, result)
}

// handleNotificationsInitialized handles the notifications/initialized method
// This is a notification (no 'id' field), so we don't send any response
func (s *Server) handleNotificationsInitialized(w http.ResponseWriter, request JSONRPC) {
	s.logger.Info("Received notifications/initialized")
	// No response needed for notifications
}

// handleResourcesTemplatesList handles the resources/templates/list method
func (s *Server) handleResourcesTemplatesList(w http.ResponseWriter, request JSONRPC) {
	// Log the request
	s.logger.Info("resources/templates/list request received")

	// Return an empty list of templates
	result := map[string]interface{}{
		"templates": []interface{}{},
	}

	s.writeJSONRPCResponse(w, request.ID, result)
}

// handleProvisionedEstimate handles the provisioned estimate request
func (s *Server) handleProvisionedEstimate(args map[string]interface{}) ToolCallResult {
	s.logger.Info("handling provisioned estimate request", zap.Any("args", args))

	// Extract the actual arguments if they're nested under "arguments"
	actualArgs := args
	if argsWrapper, exists := args["arguments"]; exists {
		if argsMap, ok := argsWrapper.(map[string]interface{}); ok {
			actualArgs = argsMap
			s.logger.Info("extracted nested arguments", zap.Any("actualArgs", actualArgs))
		}
	}

	// Convert actualArgs to JSON bytes for processing
	requestBytes, err := json.Marshal(actualArgs)
	if err != nil {
		s.logger.Error("failed to marshal arguments to JSON", zap.Error(err), zap.Any("actualArgs", actualArgs))
		return ToolCallResult{
			Content: []ContentBlock{{
				Type: "text",
				Text: fmt.Sprintf("Failed to marshal arguments: %v", err),
			}},
			IsError: true,
		}
	}

	// Parse and create request with defaults (like the root handler does)
	var request provisioned.EstimateRequest
	if err := json.Unmarshal(requestBytes, &request); err != nil {
		s.logger.Error("failed to unmarshal request", zap.Error(err))
		return ToolCallResult{
			Content: []ContentBlock{{
				Type: "text",
				Text: fmt.Sprintf("Failed to unmarshal request: %v", err),
			}},
			IsError: true,
		}
	}

	// Validate the request
	if err := request.Validate(); err != nil {
		s.logger.Error("request validation failed", zap.Error(err))
		return ToolCallResult{
			Content: []ContentBlock{{
				Type: "text",
				Text: fmt.Sprintf("Request validation failed: %v", err),
			}},
			IsError: true,
		}
	}

	// Create request with defaults based on type (like root handler does)
	if request.Search != nil {
		request = createSearchRequestWithDefaults(requestBytes)
	} else if request.Vector != nil {
		request = createVectorRequestWithDefaults(requestBytes)
	} else {
		request = createTimeSeriesRequestWithDefaults(requestBytes)
	}

	// Normalize the request
	request.Normalize(s.logger)

	// Calculate the estimate
	result, err := request.Calculate()
	if err != nil {
		s.logger.Error("failed to calculate estimate", zap.Error(err), zap.Any("actualArgs", actualArgs))
		return ToolCallResult{
			Content: []ContentBlock{{
				Type: "text",
				Text: fmt.Sprintf("Calculation failed: %v", err),
			}},
			IsError: true,
		}
	}

	// Limit to top 3 cluster configurations and add description
	limitedResult := s.limitAndDescribeProvisionedResult(result)

	s.logger.Info("provisioned estimate request succeeded", zap.Any("result", limitedResult))

	// Check if caller wants structured JSON response
	structuredResponse := false
	if sr, ok := actualArgs["structuredResponse"].(bool); ok {
		structuredResponse = sr
	}

	// Format the result for Claude Desktop
	resultText := s.formatProvisionedResultAsText(limitedResult)

	// If structured response is requested, add JSON in a code block
	if structuredResponse {
		structuredJSON, err := json.MarshalIndent(limitedResult, "", "  ")
		if err != nil {
			s.logger.Error("failed to marshal structured result to JSON", zap.Error(err))
			structuredJSON = []byte("{}")
		}

		return ToolCallResult{
			Content: []ContentBlock{
				{
					Type: "text",
					Text: resultText,
				},
				{
					Type: "text",
					Text: fmt.Sprintf("\n---\n\n## Structured Data (JSON)\n\n```json\n%s\n```", string(structuredJSON)),
				},
			},
		}
	}

	// Default: return only markdown text
	return ToolCallResult{
		Content: []ContentBlock{
			{
				Type: "text",
				Text: resultText,
			},
		},
	}
}

// handleServerlessV2Estimate handles the serverless v2 estimate request
func (s *Server) handleServerlessV2Estimate(args map[string]interface{}) ToolCallResult {
	s.logger.Info("handling serverless v2 estimate request", zap.Any("args", args))

	// Extract the actual arguments if they're nested under "arguments"
	actualArgs := args
	if argsWrapper, exists := args["arguments"]; exists {
		if argsMap, ok := argsWrapper.(map[string]interface{}); ok {
			actualArgs = argsMap
			s.logger.Info("extracted nested arguments for serverless v2", zap.Any("actualArgs", actualArgs))
		}
	}

	// Forward to the serverless v2 estimate handler
	result, err := s.serverlessV2Handler.Handle(context.Background(), actualArgs)
	if err != nil {
		s.logger.Error("failed to handle serverless v2 estimate request", zap.Error(err), zap.Any("actualArgs", actualArgs))
		return ToolCallResult{
			Content: []ContentBlock{{
				Type: "text",
				Text: fmt.Sprintf("Serverless calculation failed: %v", err),
			}},
			IsError: true,
		}
	}

	s.logger.Info("serverless v2 estimate request succeeded", zap.Any("result", result))

	// Check if caller wants structured JSON response
	structuredResponse := false
	if sr, ok := actualArgs["structuredResponse"].(bool); ok {
		structuredResponse = sr
	}

	// Format the result for Claude Desktop
	resultText := s.formatServerlessResultAsText(result)

	// If structured response is requested, add JSON in a code block
	if structuredResponse {
		// Add originalRequest to the result for What-If analysis support
		// This allows the frontend to reconstruct the API call for recalculations
		resultMap, ok := result.(map[string]interface{})
		if ok {
			resultMap["originalRequest"] = actualArgs
			result = resultMap
		}

		structuredJSON, err := json.MarshalIndent(result, "", "  ")
		if err != nil {
			s.logger.Error("failed to marshal serverless result to JSON", zap.Error(err))
			structuredJSON = []byte("{}")
		}

		return ToolCallResult{
			Content: []ContentBlock{
				{
					Type: "text",
					Text: resultText,
				},
				{
					Type: "text",
					Text: fmt.Sprintf("\n---\n\n## Structured Data (JSON)\n\n```json\n%s\n```", string(structuredJSON)),
				},
			},
		}
	}

	// Default: return only markdown text
	return ToolCallResult{
		Content: []ContentBlock{
			{
				Type: "text",
				Text: resultText,
			},
		},
	}
}

// writeJSONRPCResponse writes a JSON-RPC response
func (s *Server) writeJSONRPCResponse(w http.ResponseWriter, id interface{}, result interface{}) {
	response := JSONRPC{
		JSONRPC: "2.0",
		ID:      id,
		Result:  result,
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(response); err != nil {
		s.logger.Error("failed to encode JSON-RPC response", zap.Error(err))
		http.Error(w, "Internal server error", http.StatusInternalServerError)
	}
}

// writeJSONRPCError writes a JSON-RPC error response
func (s *Server) writeJSONRPCError(w http.ResponseWriter, id interface{}, code int, message string, err error) {
	jsonRPCError := JSONRPCError{
		Code:    code,
		Message: message,
	}

	if err != nil {
		jsonRPCError.Data = err.Error()
	}

	response := JSONRPC{
		JSONRPC: "2.0",
		ID:      id,
		Error:   &jsonRPCError,
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(response); err != nil {
		s.logger.Error("failed to encode JSON-RPC error response", zap.Error(err))
		http.Error(w, "Internal server error", http.StatusInternalServerError)
	}
}

// mapToStruct maps a map[string]interface{} to a struct
func mapToStruct(m map[string]interface{}, v interface{}) error {
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}

// Helper functions to create requests with defaults (mirroring root handler approach)
func createTimeSeriesRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		TimeSeries: provisioned.GetDefaultTimeSeriesRequest(),
	}

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		zap.L().Warn("failed to unmarshal time series request, using defaults", zap.Error(err))
	}
	return
}

// limitAndDescribeProvisionedResult limits the cluster configurations to top 3 and adds descriptions
func (s *Server) limitAndDescribeProvisionedResult(result provisioned.EstimateResponse) map[string]interface{} {
	// Limit to top 3 cluster configurations (they're already sorted by cost)
	clusterConfigs := result.ClusterConfigs
	if len(clusterConfigs) > 3 {
		clusterConfigs = clusterConfigs[:3]
	}

	// Create enhanced response with descriptions
	enhancedResult := map[string]interface{}{
		"summary": map[string]interface{}{
			"description":         "Cost estimate for provisioned OpenSearch domain showing the top 3 most cost-effective configurations",
			"totalConfigurations": len(result.ClusterConfigs),
			"showingTop":          len(clusterConfigs),
			"workloadType":        s.getWorkloadType(result),
		},
		"workloadDetails": map[string]interface{}{
			"activePrimaryShards":           result.ActivePrimaryShards,
			"totalActiveShards":             result.TotalActiveShards,
			"totalHotStorage":               result.TotalHotStorage,
			"totalWarmStorage":              result.TotalWarmStorage,
			"totalColdStorage":              result.TotalColdStorage,
			"totalMemoryRequiredForVectors": result.TotalMemoryRequiredForVectors,
		},
		"topClusterConfigurations": s.enhanceClusterConfigs(clusterConfigs),
		"originalRequest": map[string]interface{}{
			"searchRequest":     result.SearchRequest,
			"timeSeriesRequest": result.TimeSeriesRequest,
			"vectorRequest":     result.VectorRequest,
		},
	}

	return enhancedResult
}

// getWorkloadType determines the workload type from the result
func (s *Server) getWorkloadType(result provisioned.EstimateResponse) string {
	if result.SearchRequest != nil {
		return "search"
	} else if result.VectorRequest != nil {
		return "vector"
	} else if result.TimeSeriesRequest != nil {
		return "timeSeries"
	}
	return "unknown"
}

// enhanceClusterConfigs adds descriptions and explanations to cluster configurations
func (s *Server) enhanceClusterConfigs(configs []provisioned.ClusterConfig) []map[string]interface{} {
	enhanced := make([]map[string]interface{}, len(configs))

	for i, config := range configs {
		enhanced[i] = map[string]interface{}{
			"rank":        i + 1,
			"description": s.generateConfigDescription(config, i),
			"costs": map[string]interface{}{
				"totalMonthlyCost":          config.TotalCost,
				"discountedTotal":           config.DiscountedTotal,
				"enterpriseDiscountPercent": config.Edp,
				"additionalDiscount":        config.Discount,
			},
			"architecture":  s.describeArchitecture(config),
			"configuration": config,
		}
	}

	return enhanced
}

// generateConfigDescription creates a human-readable description of the cluster configuration
func (s *Server) generateConfigDescription(config provisioned.ClusterConfig, rank int) string {
	var description string

	switch rank {
	case 0:
		description = "Most cost-effective option - "
	case 1:
		description = "Second option - "
	case 2:
		description = "Third option - "
	default:
		description = "Alternative configuration - "
	}

	if config.HotNodes != nil {
		description += fmt.Sprintf("%d x %s hot nodes", config.HotNodes.Count, config.HotNodes.Type)
	}

	if config.WarmNodes != nil {
		description += fmt.Sprintf(", %d x %s warm nodes", config.WarmNodes.Count, config.WarmNodes.Type)
	}

	if config.LeaderNodes != nil {
		description += fmt.Sprintf(", %d x %s dedicated manager nodes", config.LeaderNodes.Count, config.LeaderNodes.Type)
	}

	return description
}

// describeArchitecture provides details about the cluster architecture
func (s *Server) describeArchitecture(config provisioned.ClusterConfig) map[string]interface{} {
	arch := map[string]interface{}{}

	if config.HotNodes != nil {
		arch["hotNodes"] = map[string]interface{}{
			"instanceType":       config.HotNodes.Type,
			"count":              config.HotNodes.Count,
			"family":             config.HotNodes.Family,
			"totalMemory":        config.HotNodes.Memory,
			"jvmMemory":          config.HotNodes.JVMMemory,
			"vectorMemory":       config.HotNodes.VectorMemory,
			"availableCPUs":      config.HotNodes.AvailableCPUs,
			"hasInternalStorage": config.HotNodes.HasInternalStorage,
			"storageRequired":    config.HotNodes.StorageRequired,
			"maxSearchThreads":   config.HotNodes.MaxNumberOfSearchThreads,
			"maxIndexThreads":    config.HotNodes.MaxNumberOfWriteThreads,
		}
	}

	if config.WarmNodes != nil {
		arch["warmNodes"] = map[string]interface{}{
			"instanceType":    config.WarmNodes.Type,
			"count":           config.WarmNodes.Count,
			"storageRequired": config.WarmNodes.StorageRequired,
		}
	}

	if config.LeaderNodes != nil {
		arch["leaderNodes"] = map[string]interface{}{
			"instanceType": config.LeaderNodes.Type,
			"count":        config.LeaderNodes.Count,
			"family":       config.LeaderNodes.Family,
		}
	}

	if config.ColdStorage != nil {
		arch["coldStorage"] = map[string]interface{}{
			"storageRequired":    config.ColdStorage.StorageRequired,
			"managedStorageCost": config.ColdStorage.ManagedStorageCost,
		}
	}

	return arch
}

// formatProvisionedResultAsText converts the provisioned result to human-readable text
func (s *Server) formatProvisionedResultAsText(result map[string]interface{}) string {
	var text strings.Builder

	// Summary section
	if summary, ok := result["summary"].(map[string]interface{}); ok {
		text.WriteString("# OpenSearch Provisioned Domain Cost Estimate\n\n")
		text.WriteString(fmt.Sprintf("**Workload Type:** %v\n", summary["workloadType"]))
		text.WriteString(fmt.Sprintf("**Showing:** Top %v of %v configurations\n\n", summary["showingTop"], summary["totalConfigurations"]))
	}

	// Workload details
	if details, ok := result["workloadDetails"].(map[string]interface{}); ok {
		text.WriteString("## Workload Requirements\n")
		if shards, ok := details["activePrimaryShards"]; ok && shards != nil {
			text.WriteString(fmt.Sprintf("- **Primary Shards:** %v\n", shards))
		}
		if totalShards, ok := details["totalActiveShards"]; ok && totalShards != nil {
			text.WriteString(fmt.Sprintf("- **Total Active Shards:** %v\n", totalShards))
		}
		// Always show hot storage, even if 0 (important for timeseries workloads)
		if hotStorage, ok := details["totalHotStorage"]; ok && hotStorage != nil {
			hotStorageValue := hotStorage.(float64)
			if hotStorageValue >= 0 {
				text.WriteString(fmt.Sprintf("- **Hot Storage:** %.2f GB\n", hotStorageValue))
			}
		}
		if warmStorage, ok := details["totalWarmStorage"]; ok && warmStorage != nil && warmStorage.(float64) > 0 {
			text.WriteString(fmt.Sprintf("- **Warm Storage:** %.2f GB\n", warmStorage))
		}
		if coldStorage, ok := details["totalColdStorage"]; ok && coldStorage != nil && coldStorage.(float64) > 0 {
			text.WriteString(fmt.Sprintf("- **Cold Storage:** %.2f GB\n", coldStorage))
		}
		if vectorMemory, ok := details["totalMemoryRequiredForVectors"]; ok && vectorMemory != nil && vectorMemory.(float64) > 0 {
			text.WriteString(fmt.Sprintf("- **Vector Memory Required:** %.2f GB\n", vectorMemory))
		}
		text.WriteString("\n")
	}

	// Cluster configurations
	if configs, ok := result["topClusterConfigurations"].([]map[string]interface{}); ok {
		text.WriteString("## Top 3 Cluster Configurations\n\n")
		for _, config := range configs {
			text.WriteString(fmt.Sprintf("### %v. %v\n", config["rank"], config["description"]))

			if costs, ok := config["costs"].(map[string]interface{}); ok {
				if totalCost, ok := costs["totalMonthlyCost"]; ok {
					text.WriteString(fmt.Sprintf("**Monthly Cost:** $%.2f\n", totalCost))
				}
				if discountedTotal, ok := costs["discountedTotal"]; ok && discountedTotal.(float64) > 0 {
					text.WriteString(fmt.Sprintf("**After Discounts:** $%.2f\n", discountedTotal))
				}
				if edp, ok := costs["enterpriseDiscountPercent"]; ok && edp.(float64) > 0 {
					text.WriteString(fmt.Sprintf("**Enterprise Discount:** %.1f%%\n", edp))
				}
			}

			if arch, ok := config["architecture"].(map[string]interface{}); ok {
				if hotNodes, ok := arch["hotNodes"].(map[string]interface{}); ok {
					text.WriteString(fmt.Sprintf("- **Hot Nodes:** %v x %v (%v family)\n",
						hotNodes["count"], hotNodes["instanceType"], hotNodes["family"]))
					text.WriteString(fmt.Sprintf("  - Memory: %.1f GB (JVM: %.1f GB)\n",
						hotNodes["totalMemory"], hotNodes["jvmMemory"]))
					text.WriteString(fmt.Sprintf("  - CPUs: %v, Storage: %v GB\n",
						hotNodes["availableCPUs"], hotNodes["storageRequired"]))
					if vectorMem, ok := hotNodes["vectorMemory"]; ok && vectorMem.(float64) > 0 {
						text.WriteString(fmt.Sprintf("  - Vector Memory: %.1f GB\n", vectorMem))
					}
					// Add thread pool information
					if maxSearchThreads, ok := hotNodes["maxSearchThreads"]; ok {
						text.WriteString(fmt.Sprintf("  - Max Search Threads: %v\n", maxSearchThreads))
					}
					if maxIndexThreads, ok := hotNodes["maxIndexThreads"]; ok {
						text.WriteString(fmt.Sprintf("  - Max Index/Write Threads: %v\n", maxIndexThreads))
					}
				}
				if warmNodes, ok := arch["warmNodes"].(map[string]interface{}); ok {
					text.WriteString(fmt.Sprintf("- **Warm Nodes:** %v x %v\n",
						warmNodes["count"], warmNodes["instanceType"]))
					text.WriteString(fmt.Sprintf("  - Storage: %v GB\n", warmNodes["storageRequired"]))
				}
				if leaderNodes, ok := arch["leaderNodes"].(map[string]interface{}); ok {
					text.WriteString(fmt.Sprintf("- **Manager Nodes:** %v x %v (%v family)\n",
						leaderNodes["count"], leaderNodes["instanceType"], leaderNodes["family"]))
				}
				if coldStorage, ok := arch["coldStorage"].(map[string]interface{}); ok {
					if storage, ok := coldStorage["storageRequired"]; ok && storage.(float64) > 0 {
						text.WriteString(fmt.Sprintf("- **Cold Storage:** %.1f GB\n", storage))
					}
				}
			}

			// Add pricing breakdown
			if originalConfig, ok := config["configuration"]; ok {
				text.WriteString("\n**Pricing Details:**\n")
				if configMap, ok := originalConfig.(map[string]interface{}); ok {
					if hotNodes, ok := configMap["hotNodes"]; ok {
						if hnMap, ok := hotNodes.(map[string]interface{}); ok {
							if prices, ok := hnMap["price"]; ok {
								if priceMap, ok := prices.(map[string]interface{}); ok {
									if onDemand, ok := priceMap["OnDemand"]; ok {
										if odMap, ok := onDemand.(map[string]interface{}); ok {
											if totalCost, ok := odMap["totalCost"]; ok {
												text.WriteString(fmt.Sprintf("- On-Demand: $%.2f/month\n", totalCost))
											}
										}
									}
								}
							}
						}
					}
				}
			}
			text.WriteString("\n")
		}
	}

	return text.String()
}

// formatServerlessResultAsText converts the serverless result to human-readable text
func (s *Server) formatServerlessResultAsText(result interface{}) string {
	var text strings.Builder

	text.WriteString("# OpenSearch Serverless Cost Estimate\n\n")

	// Convert to map for easier access
	data, err := json.Marshal(result)
	if err != nil {
		return "Error formatting serverless result"
	}

	var resultMap map[string]interface{}
	if err := json.Unmarshal(data, &resultMap); err != nil {
		return "Error parsing serverless result"
	}

	// Add region and pricing information
	if region, ok := resultMap["region"].(string); ok {
		text.WriteString(fmt.Sprintf("**Region:** %s\n", region))
	}

	if price, ok := resultMap["price"].(map[string]interface{}); ok {
		if totalCost, ok := price["totalPrice"]; ok {
			text.WriteString(fmt.Sprintf("**Total Monthly Cost:** $%.2f\n", totalCost))
		}
		if edp, ok := resultMap["edp"]; ok && edp.(float64) > 0 {
			text.WriteString(fmt.Sprintf("**Enterprise Discount:** %.1f%%\n", edp))
		}
		text.WriteString("\n")

		// Cost breakdown
		text.WriteString("## Cost Breakdown\n")
		if ingestCost, ok := price["dailyIngestOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Ingestion:** $%.2f/day\n", ingestCost))
		}
		if searchCost, ok := price["dailySearchOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Search/Query:** $%.2f/day\n", searchCost))
		}
		if s3Cost, ok := price["monthlyS3Cost"]; ok {
			text.WriteString(fmt.Sprintf("- **Storage (S3):** $%.2f/month\n", s3Cost))
		}
		text.WriteString("\n")
	}

	// Ingestion workload details
	if ingest, ok := resultMap["ingest"].(map[string]interface{}); ok {
		text.WriteString("## Ingestion Workload\n")
		if minRate, ok := ingest["minIndexingRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Min Indexing Rate:** %.1f GB/hour\n", minRate))
		}
		if maxRate, ok := ingest["maxIndexingRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Max Indexing Rate:** %.1f GB/hour\n", maxRate))
		}
		if timeAtMax, ok := ingest["timePerDayAtMax"]; ok {
			text.WriteString(fmt.Sprintf("- **Hours at Max Rate:** %.1f hours/day\n", timeAtMax))
		}
		if minOCU, ok := ingest["minOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Min OCU:** %.1f\n", minOCU))
		}
		if maxOCU, ok := ingest["maxOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Max OCU:** %.1f\n", maxOCU))
		}
		if ocuHours, ok := ingest["OCUHoursADay"]; ok {
			text.WriteString(fmt.Sprintf("- **OCU Hours/Day:** %.1f\n", ocuHours))
		}
		if replicas, ok := ingest["replicas"]; ok {
			text.WriteString(fmt.Sprintf("- **Replicas:** %v\n", replicas))
		}
		text.WriteString("\n")
	}

	// Search workload details
	if search, ok := resultMap["search"].(map[string]interface{}); ok {
		text.WriteString("## Search Workload\n")
		if collectionSize, ok := search["collectionSize"]; ok {
			text.WriteString(fmt.Sprintf("- **Collection Size:** %.1f GB\n", collectionSize))
		}
		if minQuery, ok := search["minQueryRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Min Query Rate:** %v queries/hour\n", minQuery))
		}
		if maxQuery, ok := search["maxQueryRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Max Query Rate:** %v queries/hour\n", maxQuery))
		}
		if hoursAtMax, ok := search["hoursAtMaxRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Hours at Max Rate:** %v hours/day\n", hoursAtMax))
		}
		if minOCU, ok := search["minOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Min OCU:** %v\n", minOCU))
		}
		if maxOCU, ok := search["maxOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Max OCU:** %v\n", maxOCU))
		}
		if ocuHours, ok := search["OCUHoursADay"]; ok {
			text.WriteString(fmt.Sprintf("- **OCU Hours/Day:** %v\n", ocuHours))
		}
		if replicas, ok := search["replicas"]; ok {
			text.WriteString(fmt.Sprintf("- **Replicas:** %v\n", replicas))
		}
		text.WriteString("\n")
	}

	// Time Series workload details
	if timeSeries, ok := resultMap["timeSeries"].(map[string]interface{}); ok {
		text.WriteString("## Time Series Workload\n")
		if dailySize, ok := timeSeries["dailyIndexSize"]; ok {
			text.WriteString(fmt.Sprintf("- **Daily Index Size:** %.1f GB\n", dailySize))
		}
		if daysHot, ok := timeSeries["daysInHot"]; ok {
			text.WriteString(fmt.Sprintf("- **Days in Hot Storage:** %v\n", daysHot))
		}
		if daysWarm, ok := timeSeries["daysInWarm"]; ok && daysWarm.(int) > 0 {
			text.WriteString(fmt.Sprintf("- **Days in Warm Storage:** %v\n", daysWarm))
		}
		if hotSize, ok := timeSeries["hotIndexSize"]; ok {
			text.WriteString(fmt.Sprintf("- **Hot Index Size:** %.1f GB\n", hotSize))
		}
		if warmSize, ok := timeSeries["warmIndexSize"]; ok && warmSize.(float64) > 0 {
			text.WriteString(fmt.Sprintf("- **Warm Index Size:** %.1f GB\n", warmSize))
		}
		if minQuery, ok := timeSeries["minQueryRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Min Query Rate:** %v queries/hour\n", minQuery))
		}
		if maxQuery, ok := timeSeries["maxQueryRate"]; ok {
			text.WriteString(fmt.Sprintf("- **Max Query Rate:** %v queries/hour\n", maxQuery))
		}
		if minOCU, ok := timeSeries["minOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Min OCU:** %v\n", minOCU))
		}
		if maxOCU, ok := timeSeries["maxOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Max OCU:** %v\n", maxOCU))
		}
		if replicas, ok := timeSeries["replicas"]; ok {
			text.WriteString(fmt.Sprintf("- **Replicas:** %v\n", replicas))
		}
		text.WriteString("\n")
	}

	// Vector workload details
	if vector, ok := resultMap["vector"].(map[string]interface{}); ok {
		text.WriteString("## Vector Search Workload\n")
		if vectorCount, ok := vector["vectorCount"]; ok {
			text.WriteString(fmt.Sprintf("- **Vector Count:** %v\n", vectorCount))
		}
		if dimensions, ok := vector["dimensionsCount"]; ok {
			text.WriteString(fmt.Sprintf("- **Dimensions:** %v\n", dimensions))
		}
		if engine, ok := vector["vectorEngineType"]; ok {
			text.WriteString(fmt.Sprintf("- **Vector Engine:** %v\n", engine))
		}
		if size, ok := vector["size"]; ok {
			text.WriteString(fmt.Sprintf("- **Data Size:** %.1f GB\n", size))
		}
		if collectionSize, ok := vector["collectionSize"]; ok {
			text.WriteString(fmt.Sprintf("- **Collection Size:** %.1f GB\n", collectionSize))
		}
		if vectorMemory, ok := vector["vectorGraphSizeInMemory"]; ok {
			text.WriteString(fmt.Sprintf("- **Vector Graph Memory:** %.1f GB\n", vectorMemory))
		}
		if indexOCU, ok := vector["indexOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Index OCU:** %.1f\n", indexOCU))
		}
		if searchOCU, ok := vector["searchOCU"]; ok {
			text.WriteString(fmt.Sprintf("- **Search OCU:** %.1f\n", searchOCU))
		}
		if replicas, ok := vector["replicas"]; ok {
			text.WriteString(fmt.Sprintf("- **Replicas:** %v\n", replicas))
		}
		text.WriteString("\n")
	}

	// OCU scaling summary
	text.WriteString("## OpenSearch Compute Units (OCU) Summary\n")
	text.WriteString("OCUs automatically scale based on your workload:\n")
	text.WriteString("- **Ingestion OCUs** handle data indexing\n")
	text.WriteString("- **Search OCUs** handle queries and searches\n")
	text.WriteString("- **Minimum 2 OCUs** are always allocated for availability\n")
	text.WriteString("- **Auto-scaling** adjusts OCUs based on demand\n\n")

	return text.String()
}

func createSearchRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		Search: provisioned.GetDefaultSearchRequest(),
	}

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		zap.L().Warn("failed to unmarshal search request, using defaults", zap.Error(err))
	}
	return
}

func createVectorRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		Vector: provisioned.GetDefaultVectorRequest(),
	}

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		zap.L().Warn("failed to unmarshal vector request, using defaults", zap.Error(err))
	}
	return
}
