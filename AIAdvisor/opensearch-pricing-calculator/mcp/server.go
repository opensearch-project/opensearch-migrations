// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

import (
	"fmt"
	"net/http"

	mcpsdk "github.com/mark3labs/mcp-go/mcp"
	mcpserver "github.com/mark3labs/mcp-go/server"
)

// NewMCPGoServer creates an mcp-go SDK server instance with both tools registered.
// The server is configured with tool capabilities and automatic recovery from panics.
func NewMCPGoServer(te *ToolExecutor) *mcpserver.MCPServer {
	// Create server with tool capabilities and recovery
	s := mcpserver.NewMCPServer(
		"opensearch-pricing-calculator",
		"2.1.0",
		mcpserver.WithToolCapabilities(false), // no subscriptions
		mcpserver.WithRecovery(),              // auto-recover from panics
	)

	// Register provisioned_estimate tool
	provisionedTool := mcpsdk.Tool{
		Name:        "provisioned_estimate",
		Description: ProvisionedToolDescription,
		InputSchema: mcpsdk.ToolInputSchema{
			Type:       "object",
			Properties: extractProperties(ProvisionedRequestSchema),
			Required:   []string{}, // all fields are optional
		},
	}
	s.AddTool(provisionedTool, te.CallTool)

	// Register serverless_v2_estimate tool
	serverlessTool := mcpsdk.Tool{
		Name:        "serverless_v2_estimate",
		Description: ServerlessToolDescription,
		InputSchema: mcpsdk.ToolInputSchema{
			Type:       "object",
			Properties: extractProperties(ServerlessRequestSchema),
			Required:   []string{"ingest", "region"}, // ingest and region are required
		},
	}
	s.AddTool(serverlessTool, te.CallTool)

	return s
}

// MCPHTTPServer wraps the mcp-go StreamableHTTPServer with a health check endpoint.
// The NLB health check sends GET / on the MCP port and expects HTTP 200.
type MCPHTTPServer struct {
	streamable *mcpserver.StreamableHTTPServer
}

// NewStreamableHTTPServer creates an HTTP server for the MCP SDK with health check support.
func NewStreamableHTTPServer(te *ToolExecutor) *MCPHTTPServer {
	s := NewMCPGoServer(te)
	streamable := mcpserver.NewStreamableHTTPServer(s,
		mcpserver.WithStateLess(true),
		mcpserver.WithDisableStreaming(true),
	)
	return &MCPHTTPServer{streamable: streamable}
}

// Start listens on the given address with health check at GET / and MCP at /mcp.
func (s *MCPHTTPServer) Start(addr string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" || r.URL.Path == "/health" {
			w.WriteHeader(http.StatusOK)
			fmt.Fprint(w, "OpenSearch Calculator MCP Server")
			return
		}
		// Delegate everything else to the mcp-go SDK handler
		s.streamable.ServeHTTP(w, r)
	})
	return http.ListenAndServe(addr, mux)
}

// extractProperties extracts the "properties" field from our hand-crafted schema maps.
// Our schemas have shape: {"type": "object", "properties": {...}}.
// The mcp-go SDK expects the inner map[string]interface{} for the Properties field.
func extractProperties(schema map[string]interface{}) map[string]interface{} {
	if props, ok := schema["properties"].(map[string]interface{}); ok {
		return props
	}
	// Fallback: return empty map if properties not found
	return map[string]interface{}{}
}
