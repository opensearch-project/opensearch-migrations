// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

// JSONRPC represents a JSON-RPC 2.0 request or response
type JSONRPC struct {
	JSONRPC string      `json:"jsonrpc"`
	ID      interface{} `json:"id,omitempty"` // Can be string or number
	// For requests
	Method string                 `json:"method,omitempty"`
	Params map[string]interface{} `json:"params,omitempty"`
	// For responses
	Result interface{}   `json:"result,omitempty"`
	Error  *JSONRPCError `json:"error,omitempty"`
}

// JSONRPCError represents a JSON-RPC 2.0 error
type JSONRPCError struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

// InitializeParams represents the parameters for the initialize method
type InitializeParams struct {
	ProtocolVersion string                 `json:"protocolVersion"`
	Capabilities    map[string]interface{} `json:"capabilities"`
	ClientInfo      ClientInfo             `json:"clientInfo"`
}

// ClientInfo represents the client information
type ClientInfo struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

// InitializeResult represents the result of the initialize method
type InitializeResult struct {
	ProtocolVersion string                 `json:"protocolVersion"`
	Capabilities    map[string]interface{} `json:"capabilities,omitempty"`
}

// ToolCallParams represents the parameters for the tools/call method
type ToolCallParams struct {
	Name      string                 `json:"name"`
	Arguments map[string]interface{} `json:"arguments"`
}

// ToolCallResult represents the result of the tools/call method
type ToolCallResult struct {
	Content []ContentBlock `json:"content"`
	IsError bool           `json:"isError,omitempty"`
}

// ContentBlock represents a content block in the tool result
type ContentBlock struct {
	Type string `json:"type"`
	Text string `json:"text,omitempty"`
}

// Resource represents an MCP resource
type Resource struct {
	URI         string                 `json:"uri"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Schema      map[string]interface{} `json:"schema"`
}

// ResourcesListResult represents the result of the resources/list method
type ResourcesListResult struct {
	Resources []Resource `json:"resources"`
}

// ResourceReadParams represents the parameters for the resources/read method
type ResourceReadParams struct {
	URI    string `json:"uri"`
	Cursor string `json:"cursor,omitempty"`
}
