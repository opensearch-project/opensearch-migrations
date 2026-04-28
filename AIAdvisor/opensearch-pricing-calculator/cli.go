// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"github.com/opensearch-project/opensearch-pricing-calculator/assistant"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"github.com/opensearch-project/opensearch-pricing-calculator/mcp"
	"github.com/opensearch-project/opensearch-pricing-calculator/scheduler"
	"github.com/opensearch-project/opensearch-pricing-calculator/telemetry"

	"github.com/spf13/cobra"
	"go.uber.org/zap"
)

var rootCmd = &cobra.Command{
	Use:   "opensearch-pricing-calculator",
	Short: "OpenSearch Pricing Calculator",
	Long:  "Cost estimation tool for Amazon OpenSearch Service managed clusters and serverless collections.",
}

var serveCmd = &cobra.Command{
	Use:   "serve",
	Short: "Start the HTTP and MCP servers",
	Long:  "Start the pricing calculator as an HTTP API server (default port 5050) and MCP server (default port 8081).",
	RunE:  runServe,
}

var estimateCmd = &cobra.Command{
	Use:   "estimate",
	Short: "Estimate OpenSearch costs from the command line",
	Long:  "One-shot cost estimation for OpenSearch managed clusters or serverless collections.",
}

func initCLI() {
	rootCmd.Version = ServiceVersion
	rootCmd.AddCommand(serveCmd)
	rootCmd.AddCommand(estimateCmd)

	initEstimateProvisionedCmd()
	initEstimateServerlessCmd()
	initEstimateFromClusterCmd()
	initMCPCmd()

	// Default to serve when no subcommand is given
	rootCmd.RunE = func(cmd *cobra.Command, args []string) error {
		return runServe(cmd, args)
	}
}

func getPort(envVar string, defaultVal int) int {
	if v := os.Getenv(envVar); v != "" {
		if p, err := strconv.Atoi(v); err == nil && p > 0 && p <= 65535 {
			return p
		}
	}
	return defaultVal
}

func getEnvironment() string {
	if env := os.Getenv("ENVIRONMENT"); env != "" {
		return env
	}
	if os.Getenv("ECS_CONTAINER_METADATA_URI") != "" || os.Getenv("AWS_EXECUTION_ENV") != "" {
		return "ecs"
	}
	return "local"
}

func runServe(cmd *cobra.Command, args []string) error {
	var app application

	logsDir := "/app/logs"
	if os.Getenv("AWS_EXECUTION_ENV") == "" && os.Getenv("ECS_CONTAINER_METADATA_URI") == "" {
		logsDir = "./logs"
	}

	if err := os.MkdirAll(logsDir, 0755); err != nil {
		fmt.Printf("Failed to create logs directory: %v\n", err)
	}

	logConfig := fmt.Sprintf(`{
		"level": "info",
		"outputPaths": ["stdout", "%s/calc-app.log"],
		"errorOutputPaths": ["stderr", "%s/calc-app-errors.log"],
		"encoding": "json",
		"encoderConfig": {
			"messageKey": "message",
			"levelKey": "level",
			"levelEncoder": "lowercase",
			"timeKey": "timestamp",
			"timeEncoder": "iso8601"
		}
	}`, logsDir, logsDir)

	var cfg zap.Config
	if err := json.Unmarshal([]byte(logConfig), &cfg); err != nil {
		return fmt.Errorf("failed to parse log config: %w", err)
	}
	logger, err := cfg.Build()
	if err != nil {
		return fmt.Errorf("failed to build logger: %w", err)
	}
	zap.ReplaceGlobals(logger)

	app.logger = logger.With(
		zap.String("service", ServiceName),
		zap.String("version", ServiceVersion),
		zap.String("environment", getEnvironment()),
	)
	app.Domain = ServiceName

	tel, err := telemetry.Init(telemetry.Config{
		ServiceName:    ServiceName,
		ServiceVersion: ServiceVersion,
		Environment:    getEnvironment(),
		Logger:         app.logger,
	})
	if err != nil {
		app.logger.Error("Failed to initialize telemetry", zap.Error(err))
	} else {
		defer tel.Shutdown()
	}

	port := getPort("PORT", defaultPort)
	mcpPort := getPort("MCP_PORT", defaultMCPPort)

	app.logger.Info("Starting application.", zap.Int("port", port))

	provisionedHandler := provisioned.NewHandler(app.logger)
	serverlessHandler := serverless.NewHandler(app.logger)

	te := mcp.NewToolExecutor(app.logger, provisionedHandler, serverlessHandler)

	assistantHandler, err := assistant.NewHandler(app.logger, te)
	if err != nil {
		app.logger.Warn("Failed to initialize assistant handler", zap.Error(err))
	} else {
		app.assistantHandler = assistantHandler
		app.logger.Info("Assistant handler initialized successfully")
	}
	app.toolExecutor = te
	mcpHTTPServer := mcp.NewStreamableHTTPServer(te)

	cacheScheduler := scheduler.NewCacheScheduler(app.logger, fmt.Sprintf("http://localhost:%d", port))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	app.logger.Info("Starting cache scheduler")
	if err := cacheScheduler.Start(ctx); err != nil {
		app.logger.Error("Failed to start cache scheduler", zap.Error(err))
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	app.logger.Info("Starting MCP server.", zap.Int("port", mcpPort))
	go func() {
		if err := mcpHTTPServer.Start(fmt.Sprintf("0.0.0.0:%d", mcpPort)); err != nil {
			app.logger.Error("Unable to start the MCP server", zap.Error(err))
		}
	}()

	go func() {
		app.logger.Info("Starting main server", zap.Int("port", port))
		err := http.ListenAndServe(fmt.Sprintf("0.0.0.0:%d", port), app.routes())
		if err != nil {
			app.logger.Error("Unable to start the server", zap.Error(err))
			quit <- syscall.SIGTERM
		}
	}()

	app.logger.Info("Application started successfully",
		zap.Int("http_port", port),
		zap.Int("mcp_port", mcpPort),
		zap.Bool("scheduler_running", cacheScheduler.IsRunning()))

	<-quit
	app.logger.Info("Shutting down application")
	cacheScheduler.Stop()
	cancel()
	app.logger.Info("Application shutdown complete")

	return nil
}
