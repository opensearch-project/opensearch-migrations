// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"testing"
)

func TestIsOpenSearchOptimizedInstance(t *testing.T) {
	tests := []struct {
		name           string
		instanceType   string
		expectedResult bool
	}{
		// OpenSearch Optimized instances (should return true)
		{
			name:           "OR1 xlarge",
			instanceType:   "or1.xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OR1 2xlarge",
			instanceType:   "or1.2xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OR2 xlarge",
			instanceType:   "or2.xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OM2 xlarge",
			instanceType:   "om2.xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OI2 xlarge",
			instanceType:   "oi2.xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OR1 uppercase",
			instanceType:   "OR1.xlarge.search",
			expectedResult: true,
		},

		// Non-OpenSearch Optimized instances (should return false)
		{
			name:           "R6g xlarge",
			instanceType:   "r6g.xlarge.search",
			expectedResult: false,
		},
		{
			name:           "R5 2xlarge",
			instanceType:   "r5.2xlarge.search",
			expectedResult: false,
		},
		{
			name:           "M5 xlarge",
			instanceType:   "m5.xlarge.search",
			expectedResult: false,
		},
		{
			name:           "C5 xlarge",
			instanceType:   "c5.xlarge.search",
			expectedResult: false,
		},
		{
			name:           "I3 xlarge",
			instanceType:   "i3.xlarge.search",
			expectedResult: false,
		},
		{
			name:           "R6gd xlarge",
			instanceType:   "r6gd.xlarge.search",
			expectedResult: false,
		},
		{
			name:           "UltraWarm medium (not a hot node type)",
			instanceType:   "ultrawarm1.medium.search",
			expectedResult: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := isOpenSearchOptimizedInstance(tt.instanceType)
			if result != tt.expectedResult {
				t.Errorf("isOpenSearchOptimizedInstance(%q) = %v, want %v", tt.instanceType, result, tt.expectedResult)
			}
		})
	}
}

func TestVectorEstimateRequest_TierPercentageValidation(t *testing.T) {
	tests := []struct {
		name                   string
		warmPercentage         int
		coldPercentage         int
		expectedWarmPercentage int
		expectedColdPercentage int
	}{
		{
			name:                   "100% hot (default)",
			warmPercentage:         0,
			coldPercentage:         0,
			expectedWarmPercentage: 0,
			expectedColdPercentage: 0,
		},
		{
			name:                   "50% hot, 30% warm, 20% cold",
			warmPercentage:         30,
			coldPercentage:         20,
			expectedWarmPercentage: 30,
			expectedColdPercentage: 20,
		},
		{
			name:                   "0% hot, 70% warm, 30% cold",
			warmPercentage:         70,
			coldPercentage:         30,
			expectedWarmPercentage: 70,
			expectedColdPercentage: 30,
		},
		{
			name:                   "0% hot, 100% warm (edge case)",
			warmPercentage:         100,
			coldPercentage:         0,
			expectedWarmPercentage: 100,
			expectedColdPercentage: 0,
		},
		{
			name:                   "Exceeds 100% - should scale down",
			warmPercentage:         80,
			coldPercentage:         40,
			expectedWarmPercentage: 66, // 80 * (100/120) = 66.67 -> 66
			expectedColdPercentage: 34, // 100 - 66 = 34
		},
		{
			name:                   "Negative warm percentage - should be 0",
			warmPercentage:         -10,
			coldPercentage:         30,
			expectedWarmPercentage: 0,
			expectedColdPercentage: 30,
		},
		{
			name:                   "Negative cold percentage - should be 0",
			warmPercentage:         30,
			coldPercentage:         -10,
			expectedWarmPercentage: 30,
			expectedColdPercentage: 0,
		},
		{
			name:                   "Warm > 100 - should be capped",
			warmPercentage:         150,
			coldPercentage:         0,
			expectedWarmPercentage: 100,
			expectedColdPercentage: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				VectorEngineType: "hnsw",
				VectorCount:      1000000,
				DimensionsCount:  768,
				MaxEdges:         16,
				Replicas:         1,
				Azs:              3,
				WarmPercentage:   tt.warmPercentage,
				ColdPercentage:   tt.coldPercentage,
			}

			// Call Normalize to apply validation
			req.Normalize()

			if req.WarmPercentage != tt.expectedWarmPercentage {
				t.Errorf("Expected WarmPercentage to be %d, got %d", tt.expectedWarmPercentage, req.WarmPercentage)
			}

			if req.ColdPercentage != tt.expectedColdPercentage {
				t.Errorf("Expected ColdPercentage to be %d, got %d", tt.expectedColdPercentage, req.ColdPercentage)
			}
		})
	}
}

func TestVectorEstimateRequest_WarmInstanceTypeValidation(t *testing.T) {
	tests := []struct {
		name                     string
		warmInstanceType         string
		expectedWarmInstanceType string
	}{
		// UltraWarm instances
		{
			name:                     "Valid medium instance",
			warmInstanceType:         "ultrawarm1.medium.search",
			expectedWarmInstanceType: "ultrawarm1.medium.search",
		},
		{
			name:                     "Valid large instance",
			warmInstanceType:         "ultrawarm1.large.search",
			expectedWarmInstanceType: "ultrawarm1.large.search",
		},
		// OI2 warm instances
		{
			name:                     "Valid OI2 xlarge",
			warmInstanceType:         "oi2.xlarge.search",
			expectedWarmInstanceType: "oi2.xlarge.search",
		},
		{
			name:                     "Valid OI2 2xlarge",
			warmInstanceType:         "oi2.2xlarge.search",
			expectedWarmInstanceType: "oi2.2xlarge.search",
		},
		{
			name:                     "Valid OI2 4xlarge",
			warmInstanceType:         "oi2.4xlarge.search",
			expectedWarmInstanceType: "oi2.4xlarge.search",
		},
		{
			name:                     "Valid OI2 8xlarge",
			warmInstanceType:         "oi2.8xlarge.search",
			expectedWarmInstanceType: "oi2.8xlarge.search",
		},
		{
			name:                     "Valid OI2 16xlarge",
			warmInstanceType:         "oi2.16xlarge.search",
			expectedWarmInstanceType: "oi2.16xlarge.search",
		},
		// Invalid instances
		{
			name:                     "Invalid instance type - should be reset",
			warmInstanceType:         "invalid-instance",
			expectedWarmInstanceType: "",
		},
		{
			name:                     "Empty instance type - auto-select",
			warmInstanceType:         "",
			expectedWarmInstanceType: "",
		},
		{
			name:                     "OI2 large (not valid for warm) - should be reset",
			warmInstanceType:         "oi2.large.search",
			expectedWarmInstanceType: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				VectorEngineType: "hnsw",
				VectorCount:      1000000,
				DimensionsCount:  768,
				MaxEdges:         16,
				Replicas:         1,
				WarmPercentage:   30,
				WarmInstanceType: tt.warmInstanceType,
			}

			req.Normalize()

			if req.WarmInstanceType != tt.expectedWarmInstanceType {
				t.Errorf("Expected WarmInstanceType to be %q, got %q", tt.expectedWarmInstanceType, req.WarmInstanceType)
			}
		})
	}
}

func TestIsOI2WarmInstance(t *testing.T) {
	tests := []struct {
		name           string
		instanceType   string
		expectedResult bool
	}{
		// OI2 instances (should return true)
		{
			name:           "OI2 xlarge",
			instanceType:   "oi2.xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OI2 2xlarge",
			instanceType:   "oi2.2xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OI2 4xlarge",
			instanceType:   "oi2.4xlarge.search",
			expectedResult: true,
		},
		{
			name:           "OI2 uppercase",
			instanceType:   "OI2.xlarge.search",
			expectedResult: true,
		},
		// Non-OI2 instances (should return false)
		{
			name:           "UltraWarm medium",
			instanceType:   "ultrawarm1.medium.search",
			expectedResult: false,
		},
		{
			name:           "UltraWarm large",
			instanceType:   "ultrawarm1.large.search",
			expectedResult: false,
		},
		{
			name:           "R6g instance",
			instanceType:   "r6g.xlarge.search",
			expectedResult: false,
		},
		{
			name:           "OR1 instance",
			instanceType:   "or1.xlarge.search",
			expectedResult: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := isOI2WarmInstance(tt.instanceType)
			if result != tt.expectedResult {
				t.Errorf("isOI2WarmInstance(%q) = %v, want %v", tt.instanceType, result, tt.expectedResult)
			}
		})
	}
}

func TestGetWarmInstanceCacheSize(t *testing.T) {
	tests := []struct {
		name       string
		instanceType    string
		expectedStorageGB float64 // Max addressable warm storage per node
	}{
		// UltraWarm instances - returns cache size (same as max addressable)
		{
			name:              "UltraWarm medium",
			instanceType:      "ultrawarm1.medium.search",
			expectedStorageGB: 1536, // 1.5 TB cache
		},
		{
			name:              "UltraWarm large",
			instanceType:      "ultrawarm1.large.search",
			expectedStorageGB: 20480, // 20 TB cache
		},
		// OI2 instances - returns max addressable warm storage (5× cache)
		// Per AWS docs: cache = 80% of NVMe, max addressable = 5× cache
		{
			name:              "OI2 large",
			instanceType:      "oi2.large.search",
			expectedStorageGB: 1875, // 468 GB NVMe → 375 GB cache → 1875 GB max addressable
		},
		{
			name:              "OI2 xlarge",
			instanceType:      "oi2.xlarge.search",
			expectedStorageGB: 3750, // 937 GB NVMe → 750 GB cache → 3750 GB max addressable
		},
		{
			name:              "OI2 2xlarge",
			instanceType:      "oi2.2xlarge.search",
			expectedStorageGB: 7500, // 1875 GB NVMe → 1500 GB cache → 7500 GB max addressable
		},
		{
			name:              "OI2 4xlarge",
			instanceType:      "oi2.4xlarge.search",
			expectedStorageGB: 15000, // 3750 GB NVMe → 3000 GB cache → 15000 GB max addressable
		},
		{
			name:              "OI2 8xlarge",
			instanceType:      "oi2.8xlarge.search",
			expectedStorageGB: 30000, // 7500 GB NVMe → 6000 GB cache → 30000 GB max addressable
		},
		// Unknown instances
		{
			name:              "Unknown instance",
			instanceType:      "invalid.instance.search",
			expectedStorageGB: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Pass nil for hotInstances - values come from centralized instances package
			result := getWarmInstanceCacheSize(tt.instanceType, nil)
			if result != tt.expectedStorageGB {
				t.Errorf("getWarmInstanceCacheSize(%q) = %v, want %v", tt.instanceType, result, tt.expectedStorageGB)
			}
		})
	}
}

func TestVectorEstimateRequest_VectorCountForTier(t *testing.T) {
	tests := []struct {
		name           string
		totalVectors   int
		warmPercentage int
		coldPercentage int
		expectedHot    int
		expectedWarm   int
		expectedCold   int
	}{
		{
			name:           "100% hot",
			totalVectors:   1000000,
			warmPercentage: 0,
			coldPercentage: 0,
			expectedHot:    1000000,
			expectedWarm:   0,
			expectedCold:   0,
		},
		{
			name:           "50% hot, 30% warm, 20% cold",
			totalVectors:   1000000,
			warmPercentage: 30,
			coldPercentage: 20,
			expectedHot:    500000,
			expectedWarm:   300000,
			expectedCold:   200000,
		},
		{
			name:           "0% hot, 100% warm",
			totalVectors:   1000000,
			warmPercentage: 100,
			coldPercentage: 0,
			expectedHot:    0,
			expectedWarm:   1000000,
			expectedCold:   0,
		},
		{
			name:           "33% each tier (rounding test)",
			totalVectors:   1000000,
			warmPercentage: 33,
			coldPercentage: 34,
			expectedHot:    330000,
			expectedWarm:   330000,
			expectedCold:   340000, // Remaining goes to cold to avoid rounding errors
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				VectorCount:    tt.totalVectors,
				WarmPercentage: tt.warmPercentage,
				ColdPercentage: tt.coldPercentage,
			}

			hotCount, warmCount, coldCount := req.getVectorCountForTier()

			if hotCount != tt.expectedHot {
				t.Errorf("Expected hot vectors %d, got %d", tt.expectedHot, hotCount)
			}
			if warmCount != tt.expectedWarm {
				t.Errorf("Expected warm vectors %d, got %d", tt.expectedWarm, warmCount)
			}
			if coldCount != tt.expectedCold {
				t.Errorf("Expected cold vectors %d, got %d", tt.expectedCold, coldCount)
			}

			// Total should always equal original count
			total := hotCount + warmCount + coldCount
			if total != tt.totalVectors {
				t.Errorf("Total vectors (%d) should equal original count (%d)", total, tt.totalVectors)
			}
		})
	}
}

func TestVectorEstimateRequest_WarmStorage(t *testing.T) {
	// Note: Warm storage does NOT include replicas - UltraWarm handles replication internally
	// Formula: dimensions * 4 bytes * warmVectorCount * (1 + expansionRate/200)
	tests := []struct {
		name            string
		warmVectorCount int
		dimensions      int
		replicas        int
		expansionRate   int
		expectedMinGB   float64 // Minimum expected storage in GB
		expectedMaxGB   float64 // Maximum expected storage in GB
	}{
		{
			name:            "Zero warm vectors",
			warmVectorCount: 0,
			dimensions:      768,
			replicas:        1,
			expansionRate:   10,
			expectedMinGB:   0,
			expectedMaxGB:   0,
		},
		{
			name:            "10M vectors, 768 dims (replicas ignored)",
			warmVectorCount: 10000000,
			dimensions:      768,
			replicas:        1, // Should be ignored
			expansionRate:   10,
			expectedMinGB:   28, // ~28.6 GB * 1.05 expansion = ~30 GB (no replica)
			expectedMaxGB:   32,
		},
		{
			name:            "1M vectors, 1536 dims (replicas ignored)",
			warmVectorCount: 1000000,
			dimensions:      1536,
			replicas:        1, // Should be ignored
			expansionRate:   10,
			expectedMinGB:   5, // ~5.7 GB * 1.05 expansion = ~6 GB (no replica)
			expectedMaxGB:   7,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				DimensionsCount: tt.dimensions,
				Replicas:        tt.replicas,
				ExpansionRate:   tt.expansionRate,
			}

			storage := req.calculateWarmVectorStorage(tt.warmVectorCount)

			if storage < tt.expectedMinGB || storage > tt.expectedMaxGB {
				t.Errorf("Expected warm storage between %.2f and %.2f GB, got %.2f GB",
					tt.expectedMinGB, tt.expectedMaxGB, storage)
			}
		})
	}
}

func TestVectorEstimateRequest_ColdStorage(t *testing.T) {
	// Note: Cold storage does NOT include replicas - S3 handles durability internally
	// Formula: dimensions * 4 bytes * coldVectorCount
	tests := []struct {
		name            string
		coldVectorCount int
		dimensions      int
		replicas        int
		expectedMinGB   float64
		expectedMaxGB   float64
	}{
		{
			name:            "Zero cold vectors",
			coldVectorCount: 0,
			dimensions:      768,
			replicas:        1,
			expectedMinGB:   0,
			expectedMaxGB:   0,
		},
		{
			name:            "10M vectors, 768 dims (replicas ignored)",
			coldVectorCount: 10000000,
			dimensions:      768,
			replicas:        1,  // Should be ignored
			expectedMinGB:   28, // 10M * 768 * 4 bytes = ~28.6 GB (no replica)
			expectedMaxGB:   30,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				DimensionsCount: tt.dimensions,
				Replicas:        tt.replicas,
			}

			storage := req.calculateColdVectorStorage(tt.coldVectorCount)

			if storage < tt.expectedMinGB || storage > tt.expectedMaxGB {
				t.Errorf("Expected cold storage between %.2f and %.2f GB, got %.2f GB",
					tt.expectedMinGB, tt.expectedMaxGB, storage)
			}
		})
	}
}

func TestVectorEstimateRequest_WarmInstanceSelection(t *testing.T) {
	tests := []struct {
		name                 string
		totalWarmStorage     float64 // in GB
		warmPercentage       int
		warmInstanceType     string
		autoSelect           *bool
		expectedInstanceType string
		expectedMinNodes     int
	}{
		{
			name:                 "Zero warm storage",
			totalWarmStorage:     0,
			warmPercentage:       0,
			expectedInstanceType: "",
			expectedMinNodes:     0,
		},
		{
			name:                 "Small storage - auto-select medium",
			totalWarmStorage:     5000, // 5TB
			warmPercentage:       30,
			expectedInstanceType: "ultrawarm1.medium.search",
			expectedMinNodes:     2, // Minimum 2 for HA
		},
		{
			name:                 "Large storage - auto-select large",
			totalWarmStorage:     50000, // 50TB
			warmPercentage:       30,
			expectedInstanceType: "ultrawarm1.large.search",
			expectedMinNodes:     2,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				WarmPercentage:         tt.warmPercentage,
				WarmInstanceType:       tt.warmInstanceType,
				AutoSelectWarmInstance: tt.autoSelect,
				Azs:                    3,
			}

			warmNodes, err := req.selectWarmInstanceForVectors(tt.totalWarmStorage)

			if tt.totalWarmStorage == 0 || tt.warmPercentage == 0 {
				if warmNodes != nil {
					t.Errorf("Expected nil warmNodes for zero storage/percentage, got %+v", warmNodes)
				}
				return
			}

			if err != nil {
				t.Fatalf("selectWarmInstanceForVectors failed: %v", err)
			}

			if warmNodes == nil {
				t.Fatal("Expected warmNodes, got nil")
			}

			if warmNodes.Type != tt.expectedInstanceType {
				t.Errorf("Expected instance type %q, got %q", tt.expectedInstanceType, warmNodes.Type)
			}

			if warmNodes.Count < tt.expectedMinNodes {
				t.Errorf("Expected at least %d nodes, got %d", tt.expectedMinNodes, warmNodes.Count)
			}
		})
	}
}

func TestVectorEstimateRequest_SelectWarmInstanceForVectorsWithType(t *testing.T) {
	tests := []struct {
		name                 string
		totalWarmStorage     float64
		warmInstanceType     string
		expectedInstanceType string
		expectedMinNodes     int
		expectNil            bool
	}{
		{
			name:                 "Zero storage returns nil",
			totalWarmStorage:     0,
			warmInstanceType:     "ultrawarm1.medium.search",
			expectedInstanceType: "",
			expectedMinNodes:     0,
			expectNil:            true,
		},
		{
			name:                 "Medium instance with small storage",
			totalWarmStorage:     5000,
			warmInstanceType:     "ultrawarm1.medium.search",
			expectedInstanceType: "ultrawarm1.medium.search",
			expectedMinNodes:     2,
			expectNil:            false,
		},
		{
			name:                 "Large instance with small storage",
			totalWarmStorage:     5000,
			warmInstanceType:     "ultrawarm1.large.search",
			expectedInstanceType: "ultrawarm1.large.search",
			expectedMinNodes:     2,
			expectNil:            false,
		},
		{
			name:                 "Medium instance with large storage",
			totalWarmStorage:     50000,
			warmInstanceType:     "ultrawarm1.medium.search",
			expectedInstanceType: "ultrawarm1.medium.search",
			expectedMinNodes:     2,
			expectNil:            false,
		},
		{
			name:                 "Large instance with large storage",
			totalWarmStorage:     50000,
			warmInstanceType:     "ultrawarm1.large.search",
			expectedInstanceType: "ultrawarm1.large.search",
			expectedMinNodes:     2,
			expectNil:            false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				Azs: 3,
			}

			// Pass nil for hotInstances - test uses UltraWarm or OI2 with fallback sizes
			warmNodes, err := req.selectWarmInstanceForVectorsWithType(tt.totalWarmStorage, tt.warmInstanceType, nil)

			if tt.expectNil {
				if warmNodes != nil {
					t.Errorf("Expected nil warmNodes, got %+v", warmNodes)
				}
				return
			}

			if err != nil {
				t.Fatalf("selectWarmInstanceForVectorsWithType failed: %v", err)
			}

			if warmNodes == nil {
				t.Fatal("Expected warmNodes, got nil")
			}

			if warmNodes.Type != tt.expectedInstanceType {
				t.Errorf("Expected instance type %q, got %q", tt.expectedInstanceType, warmNodes.Type)
			}

			if warmNodes.Count < tt.expectedMinNodes {
				t.Errorf("Expected at least %d nodes, got %d", tt.expectedMinNodes, warmNodes.Count)
			}

			if warmNodes.StorageRequired != int(tt.totalWarmStorage) {
				t.Errorf("Expected StorageRequired %d, got %d", int(tt.totalWarmStorage), warmNodes.StorageRequired)
			}
		})
	}
}

func TestVectorEstimateRequest_AutoSelectWarmInstance(t *testing.T) {
	tests := []struct {
		name           string
		autoSelect     *bool
		expectedResult bool
	}{
		{
			name:           "nil - defaults to true",
			autoSelect:     nil,
			expectedResult: true,
		},
		{
			name:           "explicit true",
			autoSelect:     boolPtr(true),
			expectedResult: true,
		},
		{
			name:           "explicit false",
			autoSelect:     boolPtr(false),
			expectedResult: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				AutoSelectWarmInstance: tt.autoSelect,
			}

			result := req.isAutoSelectWarmInstance()

			if result != tt.expectedResult {
				t.Errorf("Expected isAutoSelectWarmInstance() to be %v, got %v", tt.expectedResult, result)
			}
		})
	}
}

func TestVectorEstimateRequest_MemoryOnlyForHotTier(t *testing.T) {
	// Test that memory is only calculated for hot tier vectors
	// When 50% vectors are in warm tier, memory should be roughly half

	fullHotReq := &VectorEstimateRequest{
		VectorEngineType: "hnsw",
		VectorCount:      1000000,
		DimensionsCount:  768,
		MaxEdges:         16,
		Replicas:         1,
		WarmPercentage:   0,
		ColdPercentage:   0,
	}
	fullHotReq.Normalize()

	halfHotReq := &VectorEstimateRequest{
		VectorEngineType: "hnsw",
		VectorCount:      1000000,
		DimensionsCount:  768,
		MaxEdges:         16,
		Replicas:         1,
		WarmPercentage:   50,
		ColdPercentage:   0,
	}
	halfHotReq.Normalize()

	// Get hot vector counts
	fullHotCount, _, _ := fullHotReq.getVectorCountForTier()
	halfHotCount, _, _ := halfHotReq.getVectorCountForTier()

	// Verify hot counts
	if fullHotCount != 1000000 {
		t.Errorf("Expected full hot count to be 1000000, got %d", fullHotCount)
	}
	if halfHotCount != 500000 {
		t.Errorf("Expected half hot count to be 500000, got %d", halfHotCount)
	}

	// Calculate memory for each scenario
	fullHotReq.VectorCount = fullHotCount
	fullMemory, _, err := fullHotReq.GetRequiredMemory()
	if err != nil {
		t.Fatalf("GetRequiredMemory for full hot failed: %v", err)
	}

	halfHotReq.VectorCount = halfHotCount
	halfMemory, _, err := halfHotReq.GetRequiredMemory()
	if err != nil {
		t.Fatalf("GetRequiredMemory for half hot failed: %v", err)
	}

	// Half hot should have approximately half the memory
	ratio := fullMemory / halfMemory
	expectedRatio := 2.0
	tolerance := 0.1

	if ratio < expectedRatio-tolerance || ratio > expectedRatio+tolerance {
		t.Errorf("Expected memory ratio around %.1f, got %.2f (full: %.2f, half: %.2f)",
			expectedRatio, ratio, fullMemory, halfMemory)
	}
}

// Helper function to create bool pointer
func boolPtr(b bool) *bool {
	return &b
}

func TestWarmNodesCalculateMetrics(t *testing.T) {
	tests := []struct {
		name                  string
		warmNodes             WarmNodes
		expectedSearchThreads int
		expectedWriteThreads  int
	}{
		{
			name: "UltraWarm medium - 2 nodes",
			warmNodes: WarmNodes{
				Type:          "ultrawarm1.medium.search",
				Count:         2,
				AvailableCPUs: 4, // 2 nodes * 2 CPUs each
			},
			expectedSearchThreads: 8, // (4 * 3 / 2) + 2 = 6 + 2 = 8
			expectedWriteThreads:  4, // 4 CPUs
		},
		{
			name: "UltraWarm large - 2 nodes",
			warmNodes: WarmNodes{
				Type:          "ultrawarm1.large.search",
				Count:         2,
				AvailableCPUs: 32, // 2 nodes * 16 CPUs each
			},
			expectedSearchThreads: 50, // (32 * 3 / 2) + 2 = 48 + 2 = 50
			expectedWriteThreads:  32, // 32 CPUs
		},
		{
			name: "OI2 4xlarge - 3 nodes",
			warmNodes: WarmNodes{
				Type:          "oi2.4xlarge.search",
				Count:         3,
				AvailableCPUs: 48, // 3 nodes * 16 CPUs each
			},
			expectedSearchThreads: 75, // (48 * 3 / 2) + 3 = 72 + 3 = 75
			expectedWriteThreads:  48, // 48 CPUs
		},
		{
			name: "OI2 16xlarge - 2 nodes",
			warmNodes: WarmNodes{
				Type:          "oi2.16xlarge.search",
				Count:         2,
				AvailableCPUs: 128, // 2 nodes * 64 CPUs each
			},
			expectedSearchThreads: 194, // (128 * 3 / 2) + 2 = 192 + 2 = 194
			expectedWriteThreads:  128, // 128 CPUs
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			warmNodes := tt.warmNodes
			warmNodes.CalculateMetrics()

			if warmNodes.MaxNumberOfSearchThreads != tt.expectedSearchThreads {
				t.Errorf("Expected MaxNumberOfSearchThreads %d, got %d",
					tt.expectedSearchThreads, warmNodes.MaxNumberOfSearchThreads)
			}

			if warmNodes.MaxNumberOfWriteThreads != tt.expectedWriteThreads {
				t.Errorf("Expected MaxNumberOfWriteThreads %d, got %d",
					tt.expectedWriteThreads, warmNodes.MaxNumberOfWriteThreads)
			}
		})
	}
}
