// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestStdioMCP_FullHandshake verifies the complete MCP protocol flow over stdio:
// 1. Build the binary
// 2. Start with `mcp` subcommand
// 3. Send initialize request
// 4. Send initialized notification
// 5. List tools
// 6. Call a tool
// 7. Cleanup
func TestStdioMCP_FullHandshake(t *testing.T) {
	// Build the binary
	binaryPath := "./test-mcp-calculator"
	t.Logf("Building binary: %s", binaryPath)
	buildCmd := exec.Command("go", "build", "-o", binaryPath, ".")
	buildOutput, err := buildCmd.CombinedOutput()
	require.NoError(t, err, "Failed to build binary: %s", string(buildOutput))
	defer os.Remove(binaryPath)

	// Start the MCP server process
	t.Log("Starting MCP server process")
	cmd := exec.Command(binaryPath, "mcp")

	stdin, err := cmd.StdinPipe()
	require.NoError(t, err, "Failed to get stdin pipe")

	stdout, err := cmd.StdoutPipe()
	require.NoError(t, err, "Failed to get stdout pipe")

	stderr, err := cmd.StderrPipe()
	require.NoError(t, err, "Failed to get stderr pipe")

	// Start the process
	err = cmd.Start()
	require.NoError(t, err, "Failed to start MCP server")

	// Set a safety timeout to kill the process if test hangs
	safetyTimer := time.AfterFunc(30*time.Second, func() {
		t.Log("Safety timeout reached, killing process")
		cmd.Process.Kill()
	})
	defer safetyTimer.Stop()

	// Ensure process cleanup
	defer func() {
		stdin.Close()
		cmd.Process.Kill()
		cmd.Wait()

		// Log any stderr output for debugging
		stderrBytes, _ := io.ReadAll(stderr)
		if len(stderrBytes) > 0 {
			t.Logf("Server stderr output:\n%s", string(stderrBytes))
		}
	}()

	reader := bufio.NewReader(stdout)

	// Helper function to send a JSON-RPC request
	sendRequest := func(req map[string]interface{}) {
		reqBytes, err := json.Marshal(req)
		require.NoError(t, err, "Failed to marshal request")

		t.Logf("Sending: %s", string(reqBytes))
		_, err = fmt.Fprintf(stdin, "%s\n", string(reqBytes))
		require.NoError(t, err, "Failed to write request to stdin")
	}

	// Helper function to read a JSON-RPC response
	readResponse := func() map[string]interface{} {
		line, err := reader.ReadString('\n')
		require.NoError(t, err, "Failed to read response from stdout")

		line = strings.TrimSpace(line)
		t.Logf("Received: %s", line)

		var response map[string]interface{}
		err = json.Unmarshal([]byte(line), &response)
		require.NoError(t, err, "Failed to unmarshal response: %s", line)

		return response
	}

	// Step 1: Send initialize request
	t.Log("Step 1: Sending initialize request")
	initReq := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "initialize",
		"params": map[string]interface{}{
			"protocolVersion": "2025-03-26",
			"capabilities":    map[string]interface{}{},
			"clientInfo": map[string]interface{}{
				"name":    "test",
				"version": "1.0",
			},
		},
	}
	sendRequest(initReq)

	initResp := readResponse()
	assert.Equal(t, "2.0", initResp["jsonrpc"])
	assert.Equal(t, float64(1), initResp["id"])
	require.Contains(t, initResp, "result", "Initialize response should have result field")

	result := initResp["result"].(map[string]interface{})
	assert.Equal(t, "2025-03-26", result["protocolVersion"])
	assert.Contains(t, result, "serverInfo")

	serverInfo := result["serverInfo"].(map[string]interface{})
	assert.Equal(t, "opensearch-pricing-calculator", serverInfo["name"])
	assert.Equal(t, "2.1.0", serverInfo["version"])

	// Step 2: Send initialized notification (no response expected)
	t.Log("Step 2: Sending initialized notification")
	initNotif := map[string]interface{}{
		"jsonrpc": "2.0",
		"method":  "notifications/initialized",
	}
	sendRequest(initNotif)

	// Brief pause to allow notification processing (optional, usually not needed)
	time.Sleep(100 * time.Millisecond)

	// Step 3: List tools
	t.Log("Step 3: Listing tools")
	listReq := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      2,
		"method":  "tools/list",
	}
	sendRequest(listReq)

	listResp := readResponse()
	assert.Equal(t, "2.0", listResp["jsonrpc"])
	assert.Equal(t, float64(2), listResp["id"])
	require.Contains(t, listResp, "result")

	listResult := listResp["result"].(map[string]interface{})
	require.Contains(t, listResult, "tools")

	tools := listResult["tools"].([]interface{})
	require.Len(t, tools, 2, "Should have exactly 2 tools")

	// Verify tool names
	toolNames := make([]string, len(tools))
	for i, tool := range tools {
		toolMap := tool.(map[string]interface{})
		toolNames[i] = toolMap["name"].(string)
	}
	assert.Contains(t, toolNames, "provisioned_estimate")
	assert.Contains(t, toolNames, "serverless_v2_estimate")

	// Step 4: Call provisioned_estimate tool
	t.Log("Step 4: Calling provisioned_estimate tool")
	callReq := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      3,
		"method":  "tools/call",
		"params": map[string]interface{}{
			"name": "provisioned_estimate",
			"arguments": map[string]interface{}{
				"search": map[string]interface{}{
					"size":   100.0,
					"region": "US East (N. Virginia)",
				},
			},
		},
	}
	sendRequest(callReq)

	callResp := readResponse()
	assert.Equal(t, "2.0", callResp["jsonrpc"])
	assert.Equal(t, float64(3), callResp["id"])
	require.Contains(t, callResp, "result")

	callResult := callResp["result"].(map[string]interface{})
	require.Contains(t, callResult, "content")

	content := callResult["content"].([]interface{})
	require.NotEmpty(t, content, "Tool result should have content")

	// Verify the content is valid JSON
	firstContent := content[0].(map[string]interface{})
	assert.Equal(t, "text", firstContent["type"])
	textContent := firstContent["text"].(string)

	// Verify it's valid JSON
	var estimate map[string]interface{}
	err = json.Unmarshal([]byte(textContent), &estimate)
	require.NoError(t, err, "Tool result should be valid JSON")

	// Verify estimate structure
	require.Contains(t, estimate, "clusterConfigs", "Estimate should have clusterConfigs")
	clusterConfigs := estimate["clusterConfigs"].([]interface{})
	require.NotEmpty(t, clusterConfigs, "Should have at least one cluster config")

	t.Log("Full handshake test completed successfully")
}
