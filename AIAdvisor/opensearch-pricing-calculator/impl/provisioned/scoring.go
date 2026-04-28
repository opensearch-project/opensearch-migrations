// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"math"
	"sort"
)

// WeightProfile defines the relative importance of each scoring dimension for a given workload.
// All weights must sum to 1.0.
type WeightProfile struct {
	JVM       float64 // Shards per GB JVM (lower is better)
	CPU       float64 // Available CPUs per shard (higher is better)
	Thread    float64 // Thread pool capacity per shard (higher is better)
	Node      float64 // Node count (lower is better — fewer, more capable nodes)
	Storage   float64 // Storage headroom (higher is better)
	Cost      float64 // Total monthly cost (lower is better)
	VectorMem float64 // Vector memory allocation (higher is better, only for vector workloads)
}

// PoolStats captures the min/max values for each scoring dimension across a pool of configurations.
// Used for normalizing raw values to [0,1] range for fair comparison.
type PoolStats struct {
	MinJVM       float64
	MaxJVM       float64
	MinCPU       float64
	MaxCPU       float64
	MinThread    float64
	MaxThread    float64
	MinNode      float64
	MaxNode      float64
	MinStorage   float64
	MaxStorage   float64
	MinCost      float64
	MaxCost      float64
	MinVectorMem float64
	MaxVectorMem float64
}

// baseWeights defines the default weight profiles for each workload type at medium scale.
// Aligned with the service team's recommendations (CPU and JVM as primary health signals).
var baseWeights = map[string]WeightProfile{
	"search": {
		JVM:       0.25, // Second primary CloudWatch signal (JVMMemoryPressure)
		CPU:       0.25, // Primary CloudWatch signal (CPUUtilization)
		Thread:    0.05, // Weak factor — thread pool rejections are a symptom of CPU starvation
		Node:      0.05, // Weak tiebreaker — AWS says "start larger"
		Storage:   0.20, // Well-calibrated 80% ceiling already enforced, but headroom matters
		Cost:      0.20, // Prevents over-provisioning
		VectorMem: 0.0,
	},
	"timeSeries": {
		JVM:       0.25, // JVMMemoryPressure — critical for ingest-heavy workloads
		CPU:       0.25, // CPUUtilization — ingestion is CPU-intensive
		Thread:    0.05, // Weak factor
		Node:      0.05, // Weak tiebreaker
		Storage:   0.20, // Storage headroom matters for time-series retention
		Cost:      0.20, // Prevents over-provisioning
		VectorMem: 0.0,
	},
	"vector": {
		JVM:       0.15, // JVM less critical — native memory is the bottleneck
		CPU:       0.20, // CPU matters for vector search latency
		Thread:    0.05, // Weak factor
		Node:      0.05, // Weak tiebreaker
		Storage:   0.15, // Storage for vector data + metadata
		Cost:      0.15, // Cost is important but secondary to vector memory fit
		VectorMem: 0.25, // Vectors must fit in native memory
	},
	"s3": {
		JVM:       0.10, // S3 offloads vector work; JVM only for non-vector data
		CPU:       0.10, // Minimal compute needed on data nodes
		Thread:    0.05, // Thread pool is low priority
		Node:      0.05, // Weak tiebreaker
		Storage:   0.10, // Storage for non-vector data only
		Cost:      0.55, // Cost is the dominant factor — S3 is chosen for cost optimization
		VectorMem: 0.0,  // No vector memory on data nodes
	},
}

// getWeightProfile returns the weight profile for a given workload type and data scale.
// Data scale adjusts the base weights to reflect scale-specific priorities:
// - "small": Boosts cost importance by +0.10 (taken proportionally from resource weights)
// - "xlarge": Boosts resource weights by +0.10 (taken from cost weight)
// - "medium" or "large": Use base weights unchanged
// Unknown workload types default to "search" profile.
func getWeightProfile(workloadType, dataScale string) WeightProfile {
	base, ok := baseWeights[workloadType]
	if !ok {
		base = baseWeights["search"]
	}

	switch dataScale {
	case "small":
		resourceTotal := base.JVM + base.CPU + base.Thread + base.Node + base.Storage
		if resourceTotal > 0 {
			ratio := (resourceTotal - 0.10) / resourceTotal
			base.JVM *= ratio
			base.CPU *= ratio
			base.Thread *= ratio
			base.Node *= ratio
			base.Storage *= ratio
			base.Cost += 0.10
		}
	case "xlarge":
		if base.Cost >= 0.10 {
			resourceTotal := base.JVM + base.CPU + base.Thread + base.Node + base.Storage
			if resourceTotal > 0 {
				ratio := (resourceTotal + 0.10) / resourceTotal
				base.JVM *= ratio
				base.CPU *= ratio
				base.Thread *= ratio
				base.Node *= ratio
				base.Storage *= ratio
				base.Cost -= 0.10
			}
		}
	}

	return base
}

// applyEligibilityGate filters out configurations that fail basic resource adequacy checks.
//
// Gate 1: Shards per GB JVM must be <= 25 (bp-sharding: "no more than 25 shards per GiB of Java heap")
// Gate 2: CPU:shard ratio must be >= 0.5 (bp-instances: "~2 vCPUs per 100 GB" ≈ 0.6/shard minimum)
// Gate 3: Storage utilization must be <= 90% (reserves headroom for merges, logs, rebalancing)
// Gate 4: Vector memory must fit (VECTOR only — available native memory >= required)
func applyEligibilityGate(configs []ClusterConfig, workloadType string, totalVectorMemoryRequired float64) []ClusterConfig {
	var eligible []ClusterConfig

	for _, cc := range configs {
		if cc.HotNodes == nil {
			continue
		}

		// Gate 1: Shards per GB JVM <= 25
		if cc.HotNodes.JVMMemory > 0 {
			shardsPerGbJVM := float64(cc.HotNodes.EstimatedActiveShards) / cc.HotNodes.JVMMemory
			if shardsPerGbJVM > 25.0 {
				continue
			}
		}

		// Gate 2: CPU ratio >= 0.5
		if cc.HotNodes.EstimatedActiveShards > 0 {
			cpuRatio := float64(cc.HotNodes.AvailableCPUs) / float64(cc.HotNodes.EstimatedActiveShards)
			if cpuRatio < 0.5 {
				continue
			}
		}

		// Gate 3: Vector memory must fit (vector workloads only, excluding S3)
		if totalVectorMemoryRequired > 0 && cc.HotNodes.VectorMemory > 0 {
			if cc.HotNodes.VectorMemory < totalVectorMemoryRequired {
				continue
			}
		}

		eligible = append(eligible, cc)
	}

	return eligible
}

// computePoolStats analyzes a pool of configurations and computes min/max values
// for each scoring dimension. These statistics are used for normalization.
func computePoolStats(configs []ClusterConfig) PoolStats {
	if len(configs) == 0 {
		return PoolStats{}
	}

	stats := PoolStats{
		MinJVM:       math.MaxFloat64,
		MaxJVM:       -math.MaxFloat64,
		MinCPU:       math.MaxFloat64,
		MaxCPU:       -math.MaxFloat64,
		MinThread:    math.MaxFloat64,
		MaxThread:    -math.MaxFloat64,
		MinNode:      math.MaxFloat64,
		MaxNode:      -math.MaxFloat64,
		MinStorage:   math.MaxFloat64,
		MaxStorage:   -math.MaxFloat64,
		MinCost:      math.MaxFloat64,
		MaxCost:      -math.MaxFloat64,
		MinVectorMem: math.MaxFloat64,
		MaxVectorMem: -math.MaxFloat64,
	}

	for _, cc := range configs {
		if cc.HotNodes == nil {
			continue
		}

		// JVM dimension: shards per GB JVM (inverted - lower is better)
		if cc.HotNodes.JVMMemory > 0 {
			shardsPerGbJVM := float64(cc.HotNodes.EstimatedActiveShards) / cc.HotNodes.JVMMemory
			stats.MinJVM = math.Min(stats.MinJVM, shardsPerGbJVM)
			stats.MaxJVM = math.Max(stats.MaxJVM, shardsPerGbJVM)
		}

		// CPU dimension: available CPUs per shard (higher is better)
		if cc.HotNodes.EstimatedActiveShards > 0 {
			cpuRatio := float64(cc.HotNodes.AvailableCPUs) / float64(cc.HotNodes.EstimatedActiveShards)
			stats.MinCPU = math.Min(stats.MinCPU, cpuRatio)
			stats.MaxCPU = math.Max(stats.MaxCPU, cpuRatio)
		}

		// Thread dimension: (search threads + write threads) per shard (higher is better)
		if cc.HotNodes.EstimatedActiveShards > 0 {
			totalThreads := cc.HotNodes.MaxNumberOfSearchThreads + cc.HotNodes.MaxNumberOfWriteThreads
			threadRatio := float64(totalThreads) / float64(cc.HotNodes.EstimatedActiveShards)
			stats.MinThread = math.Min(stats.MinThread, threadRatio)
			stats.MaxThread = math.Max(stats.MaxThread, threadRatio)
		}

		// Node count dimension (lower is better)
		nodeCount := float64(cc.HotNodes.Count)
		stats.MinNode = math.Min(stats.MinNode, nodeCount)
		stats.MaxNode = math.Max(stats.MaxNode, nodeCount)

		// Storage dimension: storage per node (inverted — lower per-node usage = more headroom)
		if cc.HotNodes.Count > 0 {
			storagePerNode := float64(cc.HotNodes.StorageRequired)
			stats.MinStorage = math.Min(stats.MinStorage, storagePerNode)
			stats.MaxStorage = math.Max(stats.MaxStorage, storagePerNode)
		}

		// Cost dimension (inverted - lower is better)
		stats.MinCost = math.Min(stats.MinCost, cc.TotalCost)
		stats.MaxCost = math.Max(stats.MaxCost, cc.TotalCost)

		// Vector memory dimension (higher is better, only for vector workloads)
		stats.MinVectorMem = math.Min(stats.MinVectorMem, cc.HotNodes.VectorMemory)
		stats.MaxVectorMem = math.Max(stats.MaxVectorMem, cc.HotNodes.VectorMemory)
	}

	return stats
}

// normalize maps a raw value to [0,1] range based on min/max from the pool.
// If min == max, returns 1.0 (all values are equal, so they're all optimal).
// If inverted=true, lower raw values map to higher scores (e.g., cost, shards-per-GB-JVM).
func normalize(value, min, max float64, inverted bool) float64 {
	if math.Abs(max-min) < 0.0001 {
		return 1.0
	}

	if inverted {
		return (max - value) / (max - min)
	}

	return (value - min) / (max - min)
}

// computeScore calculates a composite score for a configuration based on weighted dimensions.
// Returns a score in [0,1] where higher is better.
func computeScore(cc ClusterConfig, wp WeightProfile, ps PoolStats) float64 {
	if cc.HotNodes == nil {
		return 0.0
	}

	score := 0.0

	// JVM dimension: shards per GB JVM (inverted - lower is better)
	if cc.HotNodes.JVMMemory > 0 {
		shardsPerGbJVM := float64(cc.HotNodes.EstimatedActiveShards) / cc.HotNodes.JVMMemory
		jvmScore := normalize(shardsPerGbJVM, ps.MinJVM, ps.MaxJVM, true)
		score += wp.JVM * jvmScore
	}

	// CPU dimension: available CPUs per shard (higher is better)
	if cc.HotNodes.EstimatedActiveShards > 0 {
		cpuRatio := float64(cc.HotNodes.AvailableCPUs) / float64(cc.HotNodes.EstimatedActiveShards)
		cpuScore := normalize(cpuRatio, ps.MinCPU, ps.MaxCPU, false)
		score += wp.CPU * cpuScore
	}

	// Thread dimension: (search + write threads) per shard (higher is better)
	if cc.HotNodes.EstimatedActiveShards > 0 {
		totalThreads := cc.HotNodes.MaxNumberOfSearchThreads + cc.HotNodes.MaxNumberOfWriteThreads
		threadRatio := float64(totalThreads) / float64(cc.HotNodes.EstimatedActiveShards)
		threadScore := normalize(threadRatio, ps.MinThread, ps.MaxThread, false)
		score += wp.Thread * threadScore
	}

	// Node count dimension (inverted — fewer, more capable nodes preferred)
	nodeCount := float64(cc.HotNodes.Count)
	nodeScore := normalize(nodeCount, ps.MinNode, ps.MaxNode, true)
	score += wp.Node * nodeScore

	// Storage dimension: per-node storage (inverted — lower per-node usage = more headroom)
	if cc.HotNodes.Count > 0 {
		storagePerNode := float64(cc.HotNodes.StorageRequired)
		storageScore := normalize(storagePerNode, ps.MinStorage, ps.MaxStorage, true)
		score += wp.Storage * storageScore
	}

	// Cost dimension (inverted - lower is better)
	costScore := normalize(cc.TotalCost, ps.MinCost, ps.MaxCost, true)
	score += wp.Cost * costScore

	// Vector memory dimension (higher is better, only when weight > 0)
	if wp.VectorMem > 0 {
		vectorMemScore := normalize(cc.HotNodes.VectorMemory, ps.MinVectorMem, ps.MaxVectorMem, false)
		score += wp.VectorMem * vectorMemScore
	}

	return score
}

// ScoreAndRank applies workload-aware scoring to a pool of configurations and returns
// the top N configurations ranked by composite score (highest to lowest).
//
// Parameters:
// - configs: Pool of configurations to score (pre-sorted by cost ascending)
// - workloadType: "search", "timeSeries", "vector", or "s3"
// - dataScale: "small", "medium", "large", or "xlarge"
// - maxConfigs: Maximum number of top configurations to return
// - totalVectorMemoryRequired: total vector memory needed (0 for non-vector workloads)
func ScoreAndRank(configs []ClusterConfig, workloadType, dataScale string, maxConfigs int, totalVectorMemoryRequired float64) []ClusterConfig {
	if len(configs) == 0 {
		return []ClusterConfig{}
	}

	eligible := applyEligibilityGate(configs, workloadType, totalVectorMemoryRequired)

	if len(eligible) == 0 {
		eligible = configs
	}

	stats := computePoolStats(eligible)
	weights := getWeightProfile(workloadType, dataScale)

	type scoredConfig struct {
		config ClusterConfig
		score  float64
	}
	scored := make([]scoredConfig, len(eligible))
	for i, cc := range eligible {
		scored[i] = scoredConfig{
			config: cc,
			score:  computeScore(cc, weights, stats),
		}
	}

	sort.Slice(scored, func(i, j int) bool {
		return scored[i].score > scored[j].score
	})

	n := maxConfigs
	if n > len(scored) {
		n = len(scored)
	}

	result := make([]ClusterConfig, n)
	for i := 0; i < n; i++ {
		result[i] = scored[i].config
		result[i].Score = math.Round(scored[i].score*100) / 100
	}

	return result
}
