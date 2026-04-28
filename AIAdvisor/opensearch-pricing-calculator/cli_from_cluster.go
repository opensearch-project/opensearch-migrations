// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/opensearch-project/opensearch-pricing-calculator/cluster"
	"github.com/spf13/cobra"
	"go.uber.org/zap"
)

var (
	fcClusterURL   string
	fcUsername     string
	fcPassword     string
	fcInsecure     bool
	fcRegion       string
	fcPricing      string
	fcAzs          int
	fcOutputFormat string
	fcMetricsOnly  bool
)

var estimateFromClusterCmd = &cobra.Command{
	Use:   "from-cluster",
	Short: "Analyze a source cluster and estimate migration costs",
	Long: `Connect to an Elasticsearch or OpenSearch cluster, extract sizing metrics,
and generate a cost estimate for migrating to Amazon OpenSearch Service.

The command connects to the source cluster's REST API to gather:
  - Total data size from _cluster/stats
  - Node count and shard distribution
  - Workload type inference from index patterns

Examples:
  # Basic usage
  opensearch-pricing-calculator estimate from-cluster \
    --cluster-url http://localhost:9200 \
    --region "US East (N. Virginia)"

  # With authentication
  opensearch-pricing-calculator estimate from-cluster \
    --cluster-url https://my-cluster:9200 \
    --username admin --password secret --insecure \
    --region "US East (N. Virginia)" --pricing OnDemand

  # Metrics only (no cost estimate)
  opensearch-pricing-calculator estimate from-cluster \
    --cluster-url http://localhost:9200 --metrics-only`,
	RunE: runEstimateFromCluster,
}

func initEstimateFromClusterCmd() {
	f := estimateFromClusterCmd.Flags()

	f.StringVar(&fcClusterURL, "cluster-url", "", "Source cluster URL (required)")
	f.StringVar(&fcUsername, "username", "", "Basic auth username")
	f.StringVar(&fcPassword, "password", "", "Basic auth password")
	f.BoolVar(&fcInsecure, "insecure", false, "Skip TLS certificate verification")
	f.StringVar(&fcRegion, "region", "US East (N. Virginia)", "Target AWS region for cost estimate")
	f.StringVar(&fcPricing, "pricing", "OnDemand", "Pricing type: OnDemand, 1yrReserved, 3yrReserved")
	f.IntVar(&fcAzs, "azs", 0, "Target availability zones (0 = auto-detect from source)")
	f.StringVarP(&fcOutputFormat, "output", "o", "json", "Output format: json, table, yaml")
	f.BoolVar(&fcMetricsOnly, "metrics-only", false, "Only show source cluster metrics, skip cost estimate")

	_ = estimateFromClusterCmd.MarkFlagRequired("cluster-url")

	estimateCmd.AddCommand(estimateFromClusterCmd)
}

// fromClusterResult combines cluster metrics with the cost estimate.
type fromClusterResult struct {
	SourceCluster *cluster.ClusterMetrics `json:"sourceCluster"`
	Estimate      interface{}             `json:"estimate,omitempty"`
}

func runEstimateFromCluster(cmd *cobra.Command, args []string) error {
	analyzer := cluster.NewAnalyzer(fcClusterURL, fcUsername, fcPassword, fcInsecure)

	fmt.Fprintf(os.Stderr, "Connecting to %s...\n", fcClusterURL)

	metrics, err := analyzer.Analyze()
	if err != nil {
		return fmt.Errorf("cluster analysis failed: %w", err)
	}

	fmt.Fprintf(os.Stderr, "Cluster: %s (v%s)\n", metrics.ClusterName, metrics.Version)
	fmt.Fprintf(os.Stderr, "Data: %.1f GB across %d indices (%d shards, %d replicas)\n",
		metrics.TotalDataGB, metrics.IndexCount, metrics.ShardCount, metrics.ReplicaCount)
	fmt.Fprintf(os.Stderr, "Detected workload: %s\n", metrics.WorkloadType)

	if fcMetricsOnly {
		return outputResult(metrics, fcOutputFormat)
	}

	// Build estimate request from cluster metrics
	request := metrics.ToEstimateRequest(fcRegion, fcPricing, fcAzs)

	logger := zap.NewNop()
	if err := request.Validate(); err != nil {
		return fmt.Errorf("generated request validation failed: %w", err)
	}
	request.Normalize(logger)

	response, err := request.Calculate()
	if err != nil {
		return fmt.Errorf("cost calculation failed: %w", err)
	}

	result := fromClusterResult{
		SourceCluster: metrics,
		Estimate:      response,
	}

	if fcOutputFormat == "table" {
		return outputFromClusterTable(metrics, response)
	}

	return outputResult(result, fcOutputFormat)
}

func outputFromClusterTable(metrics *cluster.ClusterMetrics, response interface{}) error {
	fmt.Println()
	fmt.Println("Source Cluster Analysis")
	fmt.Println("=======================")
	fmt.Printf("  Cluster:       %s (v%s)\n", metrics.ClusterName, metrics.Version)
	fmt.Printf("  Nodes:         %d total, %d data\n", metrics.NodeCount, metrics.DataNodeCount)
	fmt.Printf("  Data:          %.1f GB\n", metrics.TotalDataGB)
	fmt.Printf("  Indices:       %d\n", metrics.IndexCount)
	fmt.Printf("  Shards:        %d (replicas: %d)\n", metrics.ShardCount, metrics.ReplicaCount)
	fmt.Printf("  Workload:      %s\n", metrics.WorkloadType)
	fmt.Println()

	// Delegate to the existing table formatter
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")

	fmt.Println("Target OpenSearch Cost Estimate")
	fmt.Println("===============================")
	return outputResult(response, "table")
}
