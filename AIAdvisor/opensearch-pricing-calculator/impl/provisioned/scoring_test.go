// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"math"
	"testing"
)

// Task 2 tests: Weight profiles and workload types

func TestGetWeightProfile_Search(t *testing.T) {
	profile := getWeightProfile("search", "medium")
	expected := WeightProfile{
		JVM:       0.25,
		CPU:       0.25,
		Thread:    0.05,
		Node:      0.05,
		Storage:   0.20,
		Cost:      0.20,
		VectorMem: 0.0,
	}

	if profile != expected {
		t.Errorf("Expected search medium profile %+v, got %+v", expected, profile)
	}

	// Verify weights sum to 1.0
	sum := profile.JVM + profile.CPU + profile.Thread + profile.Node + profile.Storage + profile.Cost + profile.VectorMem
	if math.Abs(sum-1.0) > 0.001 {
		t.Errorf("Weights must sum to 1.0, got %.3f", sum)
	}
}

func TestGetWeightProfile_Vector(t *testing.T) {
	profile := getWeightProfile("vector", "medium")
	expected := WeightProfile{
		JVM:       0.15,
		CPU:       0.20,
		Thread:    0.05,
		Node:      0.05,
		Storage:   0.15,
		Cost:      0.15,
		VectorMem: 0.25,
	}

	if profile != expected {
		t.Errorf("Expected vector medium profile %+v, got %+v", expected, profile)
	}

	// Verify weights sum to 1.0
	sum := profile.JVM + profile.CPU + profile.Thread + profile.Node + profile.Storage + profile.Cost + profile.VectorMem
	if math.Abs(sum-1.0) > 0.001 {
		t.Errorf("Weights must sum to 1.0, got %.3f", sum)
	}
}

func TestGetWeightProfile_TimeSeries(t *testing.T) {
	profile := getWeightProfile("timeSeries", "medium")
	expected := WeightProfile{
		JVM:       0.25,
		CPU:       0.25,
		Thread:    0.05,
		Node:      0.05,
		Storage:   0.20,
		Cost:      0.20,
		VectorMem: 0.0,
	}

	if profile != expected {
		t.Errorf("Expected timeSeries medium profile %+v, got %+v", expected, profile)
	}

	// Verify weights sum to 1.0
	sum := profile.JVM + profile.CPU + profile.Thread + profile.Node + profile.Storage + profile.Cost + profile.VectorMem
	if math.Abs(sum-1.0) > 0.001 {
		t.Errorf("Weights must sum to 1.0, got %.3f", sum)
	}
}

func TestGetWeightProfile_SmallScaleBoostsCost(t *testing.T) {
	small := getWeightProfile("search", "small")
	medium := getWeightProfile("search", "medium")

	if small.Cost <= medium.Cost {
		t.Errorf("Small scale should boost cost: small.Cost=%f, medium.Cost=%f", small.Cost, medium.Cost)
	}

	// Verify weights sum to 1.0
	sum := small.JVM + small.CPU + small.Thread + small.Node + small.Storage + small.Cost + small.VectorMem
	if math.Abs(sum-1.0) > 0.001 {
		t.Errorf("Small scale weights must sum to 1.0, got %.3f", sum)
	}
}

func TestGetWeightProfile_XLargeBoostsResources(t *testing.T) {
	xlarge := getWeightProfile("search", "xlarge")
	medium := getWeightProfile("search", "medium")

	if xlarge.Cost >= medium.Cost {
		t.Errorf("XLarge scale should reduce cost weight: xlarge.Cost=%f, medium.Cost=%f", xlarge.Cost, medium.Cost)
	}

	if xlarge.JVM <= medium.JVM {
		t.Errorf("XLarge scale should boost JVM weight: xlarge.JVM=%f, medium.JVM=%f", xlarge.JVM, medium.JVM)
	}

	// Verify weights sum to 1.0
	sum := xlarge.JVM + xlarge.CPU + xlarge.Thread + xlarge.Node + xlarge.Storage + xlarge.Cost + xlarge.VectorMem
	if math.Abs(sum-1.0) > 0.001 {
		t.Errorf("XLarge scale weights must sum to 1.0, got %.3f", sum)
	}
}

func TestGetWeightProfile_UnknownWorkloadDefaultsToSearch(t *testing.T) {
	unknown := getWeightProfile("unknown", "medium")
	search := getWeightProfile("search", "medium")

	if unknown != search {
		t.Errorf("Unknown workload should default to search: unknown=%+v, search=%+v", unknown, search)
	}
}

// Task 3 tests: Eligibility gate

func TestApplyEligibilityGate_RemovesHighShardsPerGbJVM(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards: 260, // 260 shards / 10 GB JVM = 26 > 25 threshold
				AvailableCPUs:         150, // 150 CPUs / 260 shards = 0.58 > 0.5 threshold
				JVMMemory:             10.0,
			},
			TotalCost: 500.0,
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards: 200, // 200 shards / 10 GB JVM = 20 < 25 threshold
				AvailableCPUs:         120, // 120 CPUs / 200 shards = 0.6 > 0.5 threshold
				JVMMemory:             10.0,
			},
			TotalCost: 600.0,
		},
	}

	filtered := applyEligibilityGate(configs, "search", 0)

	if len(filtered) != 1 {
		t.Errorf("Expected 1 config after filtering, got %d", len(filtered))
	}
	if len(filtered) > 0 && filtered[0].TotalCost != 600.0 {
		t.Errorf("Expected config with cost 600.0, got %f", filtered[0].TotalCost)
	}
}

func TestApplyEligibilityGate_RemovesLowCPURatio(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards: 10,  // 4 CPUs / 10 shards = 0.4 < 0.5 threshold
				AvailableCPUs:         4,
				JVMMemory:             20.0,
			},
			TotalCost: 700.0,
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards: 8,   // 4 CPUs / 8 shards = 0.5 >= 0.5 threshold
				AvailableCPUs:         4,
				JVMMemory:             20.0,
			},
			TotalCost: 800.0,
		},
	}

	filtered := applyEligibilityGate(configs, "search", 0)

	if len(filtered) != 1 {
		t.Errorf("Expected 1 config after filtering, got %d", len(filtered))
	}
	if len(filtered) > 0 && filtered[0].TotalCost != 800.0 {
		t.Errorf("Expected config with cost 800.0, got %f", filtered[0].TotalCost)
	}
}

func TestApplyEligibilityGate_KeepsAllValidConfigs(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards: 100, // 100 shards / 10 GB JVM = 10 < 25 threshold
				AvailableCPUs:         60,  // 60 CPUs / 100 shards = 0.6 > 0.5 threshold
				JVMMemory:             10.0,
			},
			TotalCost: 900.0,
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards: 200, // 200 shards / 20 GB JVM = 10 < 25 threshold
				AvailableCPUs:         120, // 120 CPUs / 200 shards = 0.6 > 0.5 threshold
				JVMMemory:             20.0,
			},
			TotalCost: 1000.0,
		},
	}

	filtered := applyEligibilityGate(configs, "search", 0)

	if len(filtered) != 2 {
		t.Errorf("Expected 2 configs after filtering, got %d", len(filtered))
	}
}

func TestApplyEligibilityGate_EmptyInput(t *testing.T) {
	configs := []ClusterConfig{}

	filtered := applyEligibilityGate(configs, "search", 0)

	if len(filtered) != 0 {
		t.Errorf("Expected empty result for empty input, got %d configs", len(filtered))
	}
}

// Task 4 tests: Score computation and ScoreAndRank

func TestComputePoolStats(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    100,
				AvailableCPUs:            60,
				MaxNumberOfSearchThreads: 95,
				MaxNumberOfWriteThreads:  60,
				Count:                    5,
				JVMMemory:                50.0, // 100 shards / 50 GB = 2.0 shards per GB
				VectorMemory:             20.0,
			},
			TotalCost: 1000.0,
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    200,
				AvailableCPUs:            150,
				MaxNumberOfSearchThreads: 230,
				MaxNumberOfWriteThreads:  150,
				Count:                    10,
				JVMMemory:                200.0, // 200 shards / 200 GB = 1.0 shards per GB (min)
				VectorMemory:             40.0,
			},
			TotalCost: 2000.0,
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    150,
				AvailableCPUs:            75,
				MaxNumberOfSearchThreads: 120,
				MaxNumberOfWriteThreads:  75,
				Count:                    7,
				JVMMemory:                50.0, // 150 shards / 50 GB = 3.0 shards per GB (max)
				VectorMemory:             30.0,
			},
			TotalCost: 1500.0,
		},
	}

	stats := computePoolStats(configs)

	// Verify min/max for JVM (shards per GB JVM)
	// Config 1: 100 shards / 50 GB = 2.0
	// Config 2: 200 shards / 200 GB = 1.0 (min)
	// Config 3: 150 shards / 50 GB = 3.0 (max)
	expectedMinJVM := 1.0
	expectedMaxJVM := 3.0
	if math.Abs(stats.MinJVM-expectedMinJVM) > 0.001 {
		t.Errorf("Expected MinJVM %.3f, got %.3f", expectedMinJVM, stats.MinJVM)
	}
	if math.Abs(stats.MaxJVM-expectedMaxJVM) > 0.001 {
		t.Errorf("Expected MaxJVM %.3f, got %.3f", expectedMaxJVM, stats.MaxJVM)
	}

	// Verify min/max for CPU ratio
	// Config 1: 60 CPUs / 100 shards = 0.6
	// Config 2: 150 CPUs / 200 shards = 0.75 (max)
	// Config 3: 75 CPUs / 150 shards = 0.5 (min)
	expectedMinCPU := 0.5
	expectedMaxCPU := 0.75
	if math.Abs(stats.MinCPU-expectedMinCPU) > 0.001 {
		t.Errorf("Expected MinCPU %.3f, got %.3f", expectedMinCPU, stats.MinCPU)
	}
	if math.Abs(stats.MaxCPU-expectedMaxCPU) > 0.001 {
		t.Errorf("Expected MaxCPU %.3f, got %.3f", expectedMaxCPU, stats.MaxCPU)
	}

	// Verify min/max for cost
	if stats.MinCost != 1000.0 {
		t.Errorf("Expected MinCost 1000.0, got %f", stats.MinCost)
	}
	if stats.MaxCost != 2000.0 {
		t.Errorf("Expected MaxCost 2000.0, got %f", stats.MaxCost)
	}

	// Verify min/max for vector memory
	if stats.MinVectorMem != 20.0 {
		t.Errorf("Expected MinVectorMem 20.0, got %f", stats.MinVectorMem)
	}
	if stats.MaxVectorMem != 40.0 {
		t.Errorf("Expected MaxVectorMem 40.0, got %f", stats.MaxVectorMem)
	}
}

func TestScoreAndRank_HighJVMBeatsCheapForLargeSearch(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    100,
				AvailableCPUs:            60,
				MaxNumberOfSearchThreads: 95,
				MaxNumberOfWriteThreads:  60,
				Count:                    3,
				JVMMemory:                100.0, // High JVM: 100 shards / 100 GB = 1.0 shards per GB
			},
			TotalCost: 2000.0, // Expensive
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    100,
				AvailableCPUs:            60,
				MaxNumberOfSearchThreads: 95,
				MaxNumberOfWriteThreads:  60,
				Count:                    3,
				JVMMemory:                30.0, // Low JVM: 100 shards / 30 GB = 3.33 shards per GB
			},
			TotalCost: 1000.0, // Cheap
		},
	}

	ranked := ScoreAndRank(configs, "search", "large", 10, 0)

	if len(ranked) != 2 {
		t.Fatalf("Expected 2 configs, got %d", len(ranked))
	}

	// For large search workload, high JVM config should rank first despite being more expensive
	if ranked[0].TotalCost != 2000.0 {
		t.Errorf("Expected expensive high-JVM config first for large search, got cost %f", ranked[0].TotalCost)
	}
}

func TestScoreAndRank_CostDominatesForSmallSearch(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    50,
				AvailableCPUs:            30,
				MaxNumberOfSearchThreads: 47,
				MaxNumberOfWriteThreads:  30,
				Count:                    3,
				JVMMemory:                50.0, // High JVM: 50 shards / 50 GB = 1.0 shards per GB
			},
			TotalCost: 2000.0, // Expensive
		},
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    50,
				AvailableCPUs:            30,
				MaxNumberOfSearchThreads: 47,
				MaxNumberOfWriteThreads:  30,
				Count:                    2,
				JVMMemory:                25.0, // Lower JVM: 50 shards / 25 GB = 2.0 shards per GB
			},
			TotalCost: 1000.0, // Cheap
		},
	}

	ranked := ScoreAndRank(configs, "search", "small", 10, 0)

	if len(ranked) != 2 {
		t.Fatalf("Expected 2 configs, got %d", len(ranked))
	}

	// For small search workload, cost should dominate, so cheap config ranks first
	if ranked[0].TotalCost != 1000.0 {
		t.Errorf("Expected cheap config first for small search, got cost %f", ranked[0].TotalCost)
	}
}

func TestScoreAndRank_RespectsMaxConfigs(t *testing.T) {
	configs := make([]ClusterConfig, 10)
	for i := 0; i < 10; i++ {
		configs[i] = ClusterConfig{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    100 + i*10,
				AvailableCPUs:            60 + i*6,
				MaxNumberOfSearchThreads: 95 + i*10,
				MaxNumberOfWriteThreads:  60 + i*6,
				Count:                    5 + i,
				JVMMemory:                50.0 + float64(i)*5,
			},
			TotalCost: 1000.0 + float64(i)*100,
		}
	}

	ranked := ScoreAndRank(configs, "search", "medium", 3, 0)

	if len(ranked) != 3 {
		t.Errorf("Expected 3 configs, got %d", len(ranked))
	}
}

func TestScoreAndRank_EmptyInput(t *testing.T) {
	configs := []ClusterConfig{}

	ranked := ScoreAndRank(configs, "search", "medium", 10, 0)

	if len(ranked) != 0 {
		t.Errorf("Expected empty result for empty input, got %d configs", len(ranked))
	}
}

func TestScoreAndRank_SingleConfig(t *testing.T) {
	configs := []ClusterConfig{
		{
			HotNodes: &HotNodes{
				EstimatedActiveShards:    100,
				AvailableCPUs:            60,
				MaxNumberOfSearchThreads: 95,
				MaxNumberOfWriteThreads:  60,
				Count:                    5,
				JVMMemory:                50.0,
			},
			TotalCost: 1000.0,
		},
	}

	ranked := ScoreAndRank(configs, "search", "medium", 10, 0)

	if len(ranked) != 1 {
		t.Errorf("Expected 1 config, got %d", len(ranked))
	}
	if ranked[0].TotalCost != 1000.0 {
		t.Errorf("Expected config with cost 1000.0, got %f", ranked[0].TotalCost)
	}
}
