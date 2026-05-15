// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"github.com/opensearch-project/opensearch-pricing-calculator/mcp"

	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/spf13/cobra"
	"go.uber.org/zap"
)

var mcpCmd = &cobra.Command{
	Use:   "mcp",
	Short: "Start MCP server over stdio",
	Long:  "Run the MCP server using stdin/stdout JSON-RPC transport for CLI integration (Migration Console, kiro-cli, Claude Desktop).",
	RunE:  runMCP,
}

func initMCPCmd() {
	rootCmd.AddCommand(mcpCmd)
}

func runMCP(cmd *cobra.Command, args []string) error {
	// Create a production logger that writes ONLY to stderr (stdout is reserved for MCP JSON-RPC protocol)
	cfg := zap.NewProductionConfig()
	cfg.OutputPaths = []string{"stderr"}
	cfg.ErrorOutputPaths = []string{"stderr"}
	logger, err := cfg.Build()
	if err != nil {
		return err
	}
	defer func() { _ = logger.Sync() }()

	logger.Info("Starting MCP server over stdio transport",
		zap.String("service", ServiceName),
		zap.String("version", ServiceVersion))

	// Create handlers
	provisionedHandler := provisioned.NewHandler(logger)
	serverlessHandler := serverless.NewHandler(logger)

	// Create tool executor
	te := mcp.NewToolExecutor(logger, provisionedHandler, serverlessHandler)

	// Create MCP server
	s := mcp.NewMCPGoServer(te)

	// Start stdio transport (blocking)
	logger.Info("MCP server starting stdio transport")
	return mcpserver.ServeStdio(s)
}
