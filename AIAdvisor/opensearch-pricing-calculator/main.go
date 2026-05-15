// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"fmt"
	"os"

	"github.com/opensearch-project/opensearch-pricing-calculator/assistant"
	_ "github.com/opensearch-project/opensearch-pricing-calculator/docs"
	"github.com/opensearch-project/opensearch-pricing-calculator/mcp"

	"go.uber.org/zap"
)

const (
	defaultPort    = 5050
	defaultMCPPort = 8081

	ServiceName    = "opensearch-pricing-calculator"
	ServiceVersion = "2.1.0"
)

type application struct {
	Domain           string
	logger           *zap.Logger
	assistantHandler *assistant.Handler
	toolExecutor     *mcp.ToolExecutor
}

//	@title			OpenSearch Pricing Calculator API
//	@version		1.0
//	@description	Cost estimation APIs for OpenSearch Managed Clusters and Serverless Collections.

// @contact.name	OpenSearch Contributors
// @contact.url	https://github.com/opensearch-project/opensearch-pricing-calculator
func main() {
	initCLI()
	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
