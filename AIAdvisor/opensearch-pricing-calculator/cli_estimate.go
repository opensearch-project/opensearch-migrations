// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"text/tabwriter"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"github.com/spf13/cobra"
	"go.uber.org/zap"
	"gopkg.in/yaml.v3"
)

// Provisioned estimate flags
var (
	provWorkload         string
	provSize             float64
	provAzs              int
	provReplicas         int
	provRegion           string
	provPricing          string
	provInputFile        string
	provOutputFormat     string
	provDedicatedManager bool
	provStorageClass     string
	provCPUsPerShard     float32
	provFreeStorage      int
	provEdp              float64
	provInstanceTypes    []string
	provTargetShardSize  int
	provExpansionRate    int
	provMinimumJVM       float64
	// Vector-specific flags
	provVectorCount      int
	provDimensions       int
	provVectorEngine     string
	provOnDisk           bool
	provCompressionLevel int
	// TimeSeries-specific flags
	provHotDays  int
	provWarmDays int
	provColdDays int
)

// Serverless estimate flags
var (
	slsType         string
	slsRegion       string
	slsInputFile    string
	slsOutputFormat string
	slsEdp          float64
	slsRedundancy   bool
	// Ingest flags
	slsMinIndexRate float64
	slsMaxIndexRate float64
	slsTimeAtMax    float64
	// TimeSeries flags
	slsDailyIndexSize float64
	slsHotDays        int
	slsWarmDays       int
	// Search flags
	slsCollectionSize float64
	// Vector flags
	slsVectorCount  int64
	slsDimensions   int64
	slsVectorEngine string
	slsOnDisk       bool
	slsCompression  int
)

var estimateProvisionedCmd = &cobra.Command{
	Use:   "provisioned",
	Short: "Estimate managed OpenSearch cluster costs",
	Long: `Estimate costs for a provisioned (managed) OpenSearch cluster.

Examples:
  # Search workload
  opensearch-pricing-calculator estimate provisioned \
    --workload search --size 200 --azs 3 --replicas 1 \
    --region "US East (N. Virginia)" --pricing OnDemand

  # Time-series workload
  opensearch-pricing-calculator estimate provisioned \
    --workload timeSeries --size 50 --hot-days 7 --warm-days 30 \
    --region "US East (N. Virginia)"

  # Vector workload
  opensearch-pricing-calculator estimate provisioned \
    --workload vector --size 100 --vector-count 10000000 --dimensions 768 \
    --vector-engine hnsw --region "US East (N. Virginia)"

  # From JSON input file
  opensearch-pricing-calculator estimate provisioned --input request.json`,
	RunE: runEstimateProvisioned,
}

var estimateServerlessCmd = &cobra.Command{
	Use:   "serverless",
	Short: "Estimate OpenSearch Serverless collection costs",
	Long: `Estimate costs for an OpenSearch Serverless collection.

Examples:
  # Time-series collection
  opensearch-pricing-calculator estimate serverless \
    --type timeSeries --daily-index-size 10 --hot-days 1 --warm-days 6 \
    --min-index-rate 5 --max-index-rate 20 \
    --region us-east-1

  # Search collection
  opensearch-pricing-calculator estimate serverless \
    --type search --collection-size 500 \
    --min-index-rate 1 --max-index-rate 5 \
    --region us-east-1

  # From JSON input file
  opensearch-pricing-calculator estimate serverless --input request.json`,
	RunE: runEstimateServerless,
}

func initEstimateProvisionedCmd() {
	f := estimateProvisionedCmd.Flags()

	// Common flags
	f.StringVar(&provWorkload, "workload", "search", "Workload type: search, timeSeries, vector")
	f.Float64Var(&provSize, "size", 0, "Data size in GB")
	f.IntVar(&provAzs, "azs", 0, "Number of availability zones (1-3)")
	f.IntVar(&provReplicas, "replicas", -1, "Number of replicas")
	f.StringVar(&provRegion, "region", "US East (N. Virginia)", "AWS region")
	f.StringVar(&provPricing, "pricing", "OnDemand", "Pricing type: OnDemand, 1yrReserved, 3yrReserved")
	f.StringVar(&provInputFile, "input", "", "JSON input file (overrides flags)")
	f.StringVarP(&provOutputFormat, "output", "o", "json", "Output format: json, table, yaml")
	f.BoolVar(&provDedicatedManager, "dedicated-manager", true, "Use dedicated manager nodes")
	f.StringVar(&provStorageClass, "storage-class", "gp3", "Storage class: gp3, io1, NVME")
	f.Float32Var(&provCPUsPerShard, "cpus-per-shard", 0, "vCPUs per shard (0 = use default)")
	f.IntVar(&provFreeStorage, "free-storage", 25, "Free storage percentage required")
	f.Float64Var(&provEdp, "edp", 0, "Enterprise Discount Program percentage")
	f.StringSliceVar(&provInstanceTypes, "instance-types", nil, "Restrict to specific instance types")
	f.IntVar(&provTargetShardSize, "target-shard-size", 0, "Target shard size in GB (0 = use default)")
	f.IntVar(&provExpansionRate, "expansion-rate", 10, "Index expansion rate percentage")
	f.Float64Var(&provMinimumJVM, "minimum-jvm", 0, "Minimum JVM heap in GB")

	// Vector-specific flags
	f.IntVar(&provVectorCount, "vector-count", 0, "Number of vectors (vector workload)")
	f.IntVar(&provDimensions, "dimensions", 384, "Vector dimensions (vector workload)")
	f.StringVar(&provVectorEngine, "vector-engine", "hnsw", "Vector engine: hnsw, hnswfp16, hnswint8, hnswpq, ivf, ivfpq")
	f.BoolVar(&provOnDisk, "on-disk", false, "Use on-disk vectors (vector workload)")
	f.IntVar(&provCompressionLevel, "compression-level", 32, "On-disk compression level: 2, 4, 8, 16, 32")

	// TimeSeries-specific flags
	f.IntVar(&provHotDays, "hot-days", 0, "Hot retention period in days (timeSeries workload)")
	f.IntVar(&provWarmDays, "warm-days", 0, "Warm retention period in days (timeSeries workload)")
	f.IntVar(&provColdDays, "cold-days", 0, "Cold retention period in days (timeSeries workload)")

	estimateCmd.AddCommand(estimateProvisionedCmd)
}

func initEstimateServerlessCmd() {
	f := estimateServerlessCmd.Flags()

	f.StringVar(&slsType, "type", "timeSeries", "Collection type: search, timeSeries, vector")
	f.StringVar(&slsRegion, "region", "us-east-1", "AWS region")
	f.StringVar(&slsInputFile, "input", "", "JSON input file (overrides flags)")
	f.StringVarP(&slsOutputFormat, "output", "o", "json", "Output format: json, table, yaml")
	f.Float64Var(&slsEdp, "edp", 0, "Enterprise Discount Program percentage")
	f.BoolVar(&slsRedundancy, "redundancy", true, "Enable multi-AZ redundancy")

	// Ingest flags
	f.Float64Var(&slsMinIndexRate, "min-index-rate", 0, "Minimum indexing rate in GB/hour")
	f.Float64Var(&slsMaxIndexRate, "max-index-rate", 0, "Maximum indexing rate in GB/hour")
	f.Float64Var(&slsTimeAtMax, "time-at-max", 24, "Hours per day at maximum indexing rate")

	// TimeSeries flags
	f.Float64Var(&slsDailyIndexSize, "daily-index-size", 0, "Daily index size in GB (timeSeries)")
	f.IntVar(&slsHotDays, "hot-days", 1, "Days in hot storage (timeSeries)")
	f.IntVar(&slsWarmDays, "warm-days", 0, "Days in warm storage (timeSeries)")

	// Search flags
	f.Float64Var(&slsCollectionSize, "collection-size", 0, "Collection size in GB (search)")

	// Vector flags
	f.Int64Var(&slsVectorCount, "vector-count", 0, "Number of vectors (vector)")
	f.Int64Var(&slsDimensions, "dimensions", 384, "Vector dimensions (vector)")
	f.StringVar(&slsVectorEngine, "vector-engine", "hnsw", "Vector engine type (vector)")
	f.BoolVar(&slsOnDisk, "on-disk", false, "Use on-disk vectors (vector)")
	f.IntVar(&slsCompression, "compression-level", 0, "On-disk compression level (vector)")

	estimateCmd.AddCommand(estimateServerlessCmd)
}

func runEstimateProvisioned(cmd *cobra.Command, args []string) error {
	logger := zap.NewNop()

	var request provisioned.EstimateRequest

	if provInputFile != "" {
		data, err := os.ReadFile(provInputFile)
		if err != nil {
			return fmt.Errorf("failed to read input file: %w", err)
		}
		if err := json.Unmarshal(data, &request); err != nil {
			return fmt.Errorf("failed to parse input file: %w", err)
		}
	} else {
		if provSize == 0 {
			return fmt.Errorf("--size is required (data size in GB)")
		}

		switch strings.ToLower(provWorkload) {
		case "search":
			sr := provisioned.GetDefaultSearchRequest()
			sr.DataSize = provSize
			sr.Region = provRegion
			sr.PricingType = provPricing
			if provAzs > 0 {
				sr.Azs = provAzs
			}
			if provReplicas >= 0 {
				sr.Replicas = provReplicas
			}
			sr.DedicatedManager = provDedicatedManager
			sr.StorageClass = provStorageClass
			if provCPUsPerShard > 0 {
				sr.CPUsPerShard = provCPUsPerShard
			}
			sr.FreeStorageRequired = provFreeStorage
			sr.Edp = provEdp
			sr.ExpansionRate = provExpansionRate
			sr.MinimumJVM = provMinimumJVM
			if provTargetShardSize > 0 {
				sr.TargetShardSize = provTargetShardSize
			}
			if len(provInstanceTypes) > 0 {
				sr.InstanceTypes = provInstanceTypes
			}
			request.Search = sr

		case "timeseries":
			tr := provisioned.GetDefaultTimeSeriesRequest()
			tr.IngestionSize = provSize
			tr.Region = provRegion
			tr.PricingType = provPricing
			if provAzs > 0 {
				tr.Azs = provAzs
			}
			if provReplicas >= 0 {
				tr.Replicas = provReplicas
			}
			if provHotDays > 0 {
				tr.HotRetentionPeriod = provHotDays
			}
			if provWarmDays > 0 {
				tr.WarmRetentionPeriod = provWarmDays
			}
			if provColdDays > 0 {
				tr.ColdRetentionPeriod = provColdDays
			}
			tr.DedicatedManager = provDedicatedManager
			tr.StorageClass = provStorageClass
			if provCPUsPerShard > 0 {
				tr.CPUsPerShard = provCPUsPerShard
			}
			tr.FreeStorageRequired = provFreeStorage
			tr.Edp = provEdp
			tr.ExpansionRate = provExpansionRate
			tr.MinimumJVM = provMinimumJVM
			if provTargetShardSize > 0 {
				tr.TargetShardSize = provTargetShardSize
			}
			if len(provInstanceTypes) > 0 {
				tr.InstanceTypes = provInstanceTypes
			}
			request.TimeSeries = tr

		case "vector":
			vr := provisioned.GetDefaultVectorRequest()
			vr.DataSize = provSize
			vr.Region = provRegion
			vr.PricingType = provPricing
			if provAzs > 0 {
				vr.Azs = provAzs
			}
			if provReplicas >= 0 {
				vr.Replicas = provReplicas
			}
			if provVectorCount > 0 {
				vr.VectorCount = provVectorCount
			}
			vr.DimensionsCount = provDimensions
			vr.VectorEngineType = provVectorEngine
			vr.OnDisk = provOnDisk
			vr.CompressionLevel = provCompressionLevel
			vr.DedicatedManager = provDedicatedManager
			vr.StorageClass = provStorageClass
			if provCPUsPerShard > 0 {
				vr.CPUsPerShard = provCPUsPerShard
			}
			vr.FreeStorageRequired = provFreeStorage
			vr.Edp = provEdp
			vr.ExpansionRate = provExpansionRate
			vr.MinimumJVM = provMinimumJVM
			if provTargetShardSize > 0 {
				vr.TargetShardSize = provTargetShardSize
			}
			if len(provInstanceTypes) > 0 {
				vr.InstanceTypes = provInstanceTypes
			}
			request.Vector = vr

		default:
			return fmt.Errorf("unknown workload type: %s (use search, timeSeries, or vector)", provWorkload)
		}
	}

	if err := request.Validate(); err != nil {
		return fmt.Errorf("invalid request: %w", err)
	}
	request.Normalize(logger)

	response, err := request.Calculate()
	if err != nil {
		return fmt.Errorf("calculation failed: %w", err)
	}

	return outputResult(response, provOutputFormat)
}

func runEstimateServerless(cmd *cobra.Command, args []string) error {
	var request serverless.EstimateRequest

	if slsInputFile != "" {
		data, err := os.ReadFile(slsInputFile)
		if err != nil {
			return fmt.Errorf("failed to read input file: %w", err)
		}
		if err := json.Unmarshal(data, &request); err != nil {
			return fmt.Errorf("failed to parse input file: %w", err)
		}
	} else {
		request.Region = slsRegion
		request.Edp = slsEdp
		request.Redundancy = &slsRedundancy

		// Ingest is always required
		request.Ingest = serverless.Ingest{
			MinIndexingRate: slsMinIndexRate,
			MaxIndexingRate: slsMaxIndexRate,
			TimePerDayAtMax: slsTimeAtMax,
		}

		switch strings.ToLower(slsType) {
		case "timeseries":
			if slsDailyIndexSize == 0 {
				return fmt.Errorf("--daily-index-size is required for timeSeries type")
			}
			request.TimeSeries = &serverless.TimeSeries{
				DailyIndexSize: slsDailyIndexSize,
				DaysInHot:      slsHotDays,
				DaysInWarm:     slsWarmDays,
			}

		case "search":
			if slsCollectionSize == 0 {
				return fmt.Errorf("--collection-size is required for search type")
			}
			request.Search = &serverless.Search{
				CollectionSize: slsCollectionSize,
			}

		case "vector":
			if slsVectorCount == 0 {
				return fmt.Errorf("--vector-count is required for vector type")
			}
			request.Vector = &serverless.Vector{
				VectorCount:      slsVectorCount,
				DimensionsCount:  slsDimensions,
				VectorEngineType: slsVectorEngine,
				OnDisk:           slsOnDisk,
				CompressionLevel: slsCompression,
			}

		default:
			return fmt.Errorf("unknown collection type: %s (use search, timeSeries, or vector)", slsType)
		}
	}

	request.Normalize()

	response, err := request.CalculateV2()
	if err != nil {
		return fmt.Errorf("calculation failed: %w", err)
	}

	return outputResult(response, slsOutputFormat)
}

func outputResult(data interface{}, format string) error {
	switch strings.ToLower(format) {
	case "json":
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(data)

	case "yaml":
		return yaml.NewEncoder(os.Stdout).Encode(data)

	case "table":
		return outputTable(data)

	default:
		return fmt.Errorf("unknown output format: %s (use json, table, or yaml)", format)
	}
}

func outputTable(data interface{}) error {
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)

	switch resp := data.(type) {
	case provisioned.EstimateResponse:
		fmt.Fprintf(w, "OpenSearch Managed Cluster Cost Estimate\n")
		fmt.Fprintf(w, "========================================\n\n")

		if resp.SearchRequest != nil {
			fmt.Fprintf(w, "Workload:\tSearch\n")
			fmt.Fprintf(w, "Data Size:\t%.1f GB\n", resp.SearchRequest.DataSize)
			fmt.Fprintf(w, "Region:\t%s\n", resp.SearchRequest.Region)
		} else if resp.TimeSeriesRequest != nil {
			fmt.Fprintf(w, "Workload:\tTime Series\n")
			fmt.Fprintf(w, "Daily Ingestion:\t%.1f GB\n", resp.TimeSeriesRequest.IngestionSize)
			fmt.Fprintf(w, "Region:\t%s\n", resp.TimeSeriesRequest.Region)
		} else if resp.VectorRequest != nil {
			fmt.Fprintf(w, "Workload:\tVector\n")
			fmt.Fprintf(w, "Data Size:\t%.1f GB\n", resp.VectorRequest.DataSize)
			fmt.Fprintf(w, "Region:\t%s\n", resp.VectorRequest.Region)
		}

		fmt.Fprintf(w, "\nHot Storage:\t%.1f GB\n", resp.TotalHotStorage)
		if resp.TotalWarmStorage > 0 {
			fmt.Fprintf(w, "Warm Storage:\t%.1f GB\n", resp.TotalWarmStorage)
		}
		if resp.TotalColdStorage > 0 {
			fmt.Fprintf(w, "Cold Storage:\t%.1f GB\n", resp.TotalColdStorage)
		}
		fmt.Fprintf(w, "Primary Shards:\t%d\n", resp.ActivePrimaryShards)
		fmt.Fprintf(w, "Total Shards:\t%d\n", resp.TotalActiveShards)

		fmt.Fprintf(w, "\n%-5s\t%-20s\t%-6s\t%-12s\t%s\n", "Config", "Instance Type", "Nodes", "Monthly Cost", "Annual Cost")
		fmt.Fprintf(w, "%-5s\t%-20s\t%-6s\t%-12s\t%s\n", "------", "--------------------", "------", "------------", "-----------")
		for i, config := range resp.ClusterConfigs {
			if config.HotNodes != nil {
				fmt.Fprintf(w, "#%-4d\t%-20s\t%-6d\t$%-11.2f\t$%.2f\n",
					i+1,
					config.HotNodes.Type,
					config.HotNodes.Count,
					config.TotalCost,
					config.TotalCost*12,
				)
			}
		}

	case serverless.EstimationResponse:
		fmt.Fprintf(w, "OpenSearch Serverless Cost Estimate\n")
		fmt.Fprintf(w, "===================================\n\n")
		fmt.Fprintf(w, "Region:\t%s\n", resp.Region)
		fmt.Fprintf(w, "Redundancy:\t%v\n", resp.Redundancy)

		fmt.Fprintf(w, "\nIngest OCU (min):\t%.1f\n", resp.Ingest.MinOCU)
		fmt.Fprintf(w, "Ingest OCU (max):\t%.1f\n", resp.Ingest.MaxOCU)

		if resp.TimeSeries != nil {
			fmt.Fprintf(w, "\nType:\tTime Series\n")
			fmt.Fprintf(w, "Hot Storage:\t%.1f GB\n", resp.TimeSeries.HotIndexSize)
			fmt.Fprintf(w, "Warm Storage:\t%.1f GB\n", resp.TimeSeries.WarmIndexSize)
		}
		if resp.Search != nil {
			fmt.Fprintf(w, "\nType:\tSearch\n")
			fmt.Fprintf(w, "Collection Size:\t%.1f GB\n", resp.Search.CollectionSize)
		}
		if resp.Vector != nil {
			fmt.Fprintf(w, "\nType:\tVector\n")
			fmt.Fprintf(w, "Collection Size:\t%.1f GB\n", resp.Vector.CollectionSize)
		}

		fmt.Fprintf(w, "\nEstimated Monthly Cost:\t$%.2f\n", resp.Price.Month.Total)
		fmt.Fprintf(w, "Estimated Annual Cost:\t$%.2f\n", resp.Price.Year.Total)
	}

	return w.Flush()
}
