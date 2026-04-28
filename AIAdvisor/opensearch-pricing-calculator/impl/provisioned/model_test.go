// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
)

func TestMaxNodeCountPerCluster(t *testing.T) {
	// Test that the MaxNodeCountPerCluster map has the expected values
	assert.Equal(t, 334, MaxNodeCountPerCluster[1], "MaxNodeCountPerCluster for 1 AZ should be 334")
	assert.Equal(t, 668, MaxNodeCountPerCluster[2], "MaxNodeCountPerCluster for 2 AZs should be 668")
	assert.Equal(t, 1002, MaxNodeCountPerCluster[3], "MaxNodeCountPerCluster for 3 AZs should be 1002")
}

func TestTimeSeriesEstimateRequest_NodeCountLimit(t *testing.T) {
	tests := []struct {
		name           string
		azs            int
		expectedLimit  int
		nodeCount      int
		shouldContinue bool
	}{
		{
			name:           "1 AZ below limit",
			azs:            1,
			expectedLimit:  334,
			nodeCount:      300,
			shouldContinue: false,
		},
		{
			name:           "1 AZ at limit",
			azs:            1,
			expectedLimit:  334,
			nodeCount:      334,
			shouldContinue: true,
		},
		{
			name:           "1 AZ above limit",
			azs:            1,
			expectedLimit:  334,
			nodeCount:      350,
			shouldContinue: true,
		},
		{
			name:           "2 AZs below limit",
			azs:            2,
			expectedLimit:  668,
			nodeCount:      600,
			shouldContinue: false,
		},
		{
			name:           "2 AZs at limit",
			azs:            2,
			expectedLimit:  668,
			nodeCount:      668,
			shouldContinue: true,
		},
		{
			name:           "2 AZs above limit",
			azs:            2,
			expectedLimit:  668,
			nodeCount:      700,
			shouldContinue: true,
		},
		{
			name:           "3 AZs below limit",
			azs:            3,
			expectedLimit:  1002,
			nodeCount:      1000,
			shouldContinue: false,
		},
		{
			name:           "3 AZs at limit",
			azs:            3,
			expectedLimit:  1002,
			nodeCount:      1002,
			shouldContinue: true,
		},
		{
			name:           "3 AZs above limit",
			azs:            3,
			expectedLimit:  1002,
			nodeCount:      1100,
			shouldContinue: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Test the condition used in the code
			result := tt.nodeCount >= MaxNodeCountPerCluster[tt.azs]
			assert.Equal(t, tt.shouldContinue, result, "Node count limit check failed")
			assert.Equal(t, tt.expectedLimit, MaxNodeCountPerCluster[tt.azs], "Expected limit doesn't match")
		})
	}
}

func TestSearchEstimateRequest_NodeCountLimit(t *testing.T) {
	// Mock instance unit for testing
	instanceUnit := price.InstanceUnit{
		CPU: 8,
		Storage: price.InstanceStorage{
			Internal: 0,
			MinEBS:   10,
		},
		InstanceType: "test-instance",
	}

	tests := []struct {
		name          string
		azs           int
		requiredCPUs  int
		storage       int
		expectedLimit int
	}{
		{
			name:          "1 AZ node count check",
			azs:           1,
			requiredCPUs:  32,
			storage:       1000,
			expectedLimit: 334,
		},
		{
			name:          "2 AZs node count check",
			azs:           2,
			requiredCPUs:  64,
			storage:       2000,
			expectedLimit: 668,
		},
		{
			name:          "3 AZs node count check",
			azs:           3,
			requiredCPUs:  96,
			storage:       3000,
			expectedLimit: 1002,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Create a mock SearchEstimateRequest with the test AZ value
			request := &SearchEstimateRequest{
				Azs: tt.azs,
			}

			// Get node count from GetRequiredNodeCount
			nodeCount, _ := instanceUnit.GetRequiredNodeCount(tt.requiredCPUs, tt.storage, tt.azs, "gp3", nil, 0.0, 0)

			// Test that the limit used in the code matches our expected limit
			limit := MaxNodeCountPerCluster[request.Azs]
			assert.Equal(t, tt.expectedLimit, limit, "Expected limit doesn't match")

			// Log the node count for debugging
			t.Logf("Node count: %d, Limit: %d", nodeCount, limit)
		})
	}
}

func TestHotNodes_CalculateMetricsWithStandby(t *testing.T) {
	tests := []struct {
		name                  string
		count                 int
		availableCPUs         int
		multiAzWithStandby    bool
		expectedWriteThreads  int
		expectedSearchThreads int
	}{
		{
			name:                  "No standby - 6 nodes, 48 CPUs",
			count:                 6,
			availableCPUs:         48,
			multiAzWithStandby:    false,
			expectedWriteThreads:  48,
			expectedSearchThreads: (48*3)/2 + 6, // 72 + 6 = 78
		},
		{
			name:                  "With standby - 6 nodes, 48 CPUs (2/3 effective)",
			count:                 6,
			availableCPUs:         48,
			multiAzWithStandby:    true,
			expectedWriteThreads:  32,           // 48 * 2/3 = 32
			expectedSearchThreads: (32*3)/2 + 4, // 48 + 4 = 52 (effective count = 6*2/3 = 4)
		},
		{
			name:                  "With standby - 9 nodes, 72 CPUs (2/3 effective)",
			count:                 9,
			availableCPUs:         72,
			multiAzWithStandby:    true,
			expectedWriteThreads:  48,           // 72 * 2/3 = 48
			expectedSearchThreads: (48*3)/2 + 6, // 72 + 6 = 78 (effective count = 9*2/3 = 6)
		},
		{
			name:                  "With standby - 3 nodes, 24 CPUs (minimum case)",
			count:                 3,
			availableCPUs:         24,
			multiAzWithStandby:    true,
			expectedWriteThreads:  16,           // 24 * 2/3 = 16
			expectedSearchThreads: (16*3)/2 + 2, // 24 + 2 = 26 (effective count = 3*2/3 = 2)
		},
		{
			name:                  "With standby - 1 node, 8 CPUs (minimum fallback)",
			count:                 1,
			availableCPUs:         8,
			multiAzWithStandby:    true,
			expectedWriteThreads:  5,           // 8 * 2/3 = 5
			expectedSearchThreads: (5*3)/2 + 1, // 7 + 1 = 8 (effective count = min(1*2/3, 1) = 1)
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			hn := &HotNodes{
				Count:         tt.count,
				AvailableCPUs: tt.availableCPUs,
			}
			hn.CalculateMetricsWithStandby(tt.multiAzWithStandby)

			assert.Equal(t, tt.expectedWriteThreads, hn.MaxNumberOfWriteThreads,
				"Write threads mismatch")
			assert.Equal(t, tt.expectedSearchThreads, hn.MaxNumberOfSearchThreads,
				"Search threads mismatch")
		})
	}
}

func TestSearchEstimateRequest_MultiAzWithStandby_Normalize(t *testing.T) {
	tests := []struct {
		name               string
		replicas           int
		multiAzWithStandby bool
		expectedReplicas   int
	}{
		{
			name:               "No standby - replicas unchanged (1)",
			replicas:           1,
			multiAzWithStandby: false,
			expectedReplicas:   1,
		},
		{
			name:               "With standby - replicas below minimum (1 -> 2)",
			replicas:           1,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
		{
			name:               "With standby - replicas at minimum (2)",
			replicas:           2,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
		{
			name:               "With standby - replicas above minimum (3)",
			replicas:           3,
			multiAzWithStandby: true,
			expectedReplicas:   3,
		},
		{
			name:               "With standby - replicas zero (0 -> 2)",
			replicas:           0,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			request := &SearchEstimateRequest{
				DataSize:           100,
				Replicas:           tt.replicas,
				MultiAzWithStandby: tt.multiAzWithStandby,
				Region:             "US East (N. Virginia)",
			}
			request.Normalize()

			assert.Equal(t, tt.expectedReplicas, request.Replicas,
				"Replicas should be minimum 2 when multiAzWithStandby is enabled")
		})
	}
}

func TestTimeSeriesEstimateRequest_MultiAzWithStandby_Normalize(t *testing.T) {
	tests := []struct {
		name               string
		replicas           int
		multiAzWithStandby bool
		expectedReplicas   int
	}{
		{
			name:               "With standby - replicas below minimum (1 -> 2)",
			replicas:           1,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
		{
			name:               "With standby - replicas at minimum (2)",
			replicas:           2,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			request := &TimeSeriesEstimateRequest{
				IngestionSize:      100,
				Replicas:           tt.replicas,
				MultiAzWithStandby: tt.multiAzWithStandby,
				Region:             "US East (N. Virginia)",
			}
			request.Normalize()

			assert.Equal(t, tt.expectedReplicas, request.Replicas,
				"Replicas should be minimum 2 when multiAzWithStandby is enabled")
		})
	}
}

func TestVectorEstimateRequest_MultiAzWithStandby_Normalize(t *testing.T) {
	tests := []struct {
		name               string
		replicas           int
		multiAzWithStandby bool
		expectedReplicas   int
	}{
		{
			name:               "With standby - replicas below minimum (1 -> 2)",
			replicas:           1,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
		{
			name:               "With standby - replicas at minimum (2)",
			replicas:           2,
			multiAzWithStandby: true,
			expectedReplicas:   2,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			request := &VectorEstimateRequest{
				DataSize:           100,
				Replicas:           tt.replicas,
				MultiAzWithStandby: tt.multiAzWithStandby,
				Region:             "US East (N. Virginia)",
			}
			request.Normalize()

			assert.Equal(t, tt.expectedReplicas, request.Replicas,
				"Replicas should be minimum 2 when multiAzWithStandby is enabled")
		})
	}
}

func TestVectorEstimateRequest_NodeCountLimit(t *testing.T) {
	// Similar to SearchEstimateRequest test but for VectorEstimateRequest
	// Mock instance unit for testing
	instanceUnit := price.InstanceUnit{
		CPU: 8,
		Storage: price.InstanceStorage{
			Internal: 0,
			MinEBS:   10,
		},
		InstanceType: "test-instance",
	}

	tests := []struct {
		name           string
		azs            int
		requiredCPUs   int
		storage        int
		vectorMemory   float64
		circuitBreaker int
		expectedLimit  int
	}{
		{
			name:           "1 AZ vector node count check",
			azs:            1,
			requiredCPUs:   32,
			storage:        1000,
			vectorMemory:   100.0,
			circuitBreaker: 80,
			expectedLimit:  334,
		},
		{
			name:           "2 AZs vector node count check",
			azs:            2,
			requiredCPUs:   64,
			storage:        2000,
			vectorMemory:   200.0,
			circuitBreaker: 80,
			expectedLimit:  668,
		},
		{
			name:           "3 AZs vector node count check",
			azs:            3,
			requiredCPUs:   96,
			storage:        3000,
			vectorMemory:   300.0,
			circuitBreaker: 80,
			expectedLimit:  1002,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Create a mock VectorEstimateRequest with the test AZ value
			request := &VectorEstimateRequest{
				Azs:                        tt.azs,
				VectorMemoryCircuitBreaker: tt.circuitBreaker,
			}

			// Get node count from GetRequiredNodeCount
			nodeCount, _ := instanceUnit.GetRequiredNodeCount(tt.requiredCPUs, tt.storage, tt.azs, "gp3", nil, tt.vectorMemory, tt.circuitBreaker)

			// Test that the limit used in the code matches our expected limit
			limit := MaxNodeCountPerCluster[request.Azs]
			assert.Equal(t, tt.expectedLimit, limit, "Expected limit doesn't match")

			// Log the node count for debugging
			t.Logf("Node count: %d, Limit: %d", nodeCount, limit)
		})
	}
}

func TestGetWarmInstanceTypesForRegion(t *testing.T) {
	tests := []struct {
		name              string
		hotInstances      map[string]price.InstanceUnit
		expectOI2Group    bool
		expectedOI2Count  int
		expectedUltraWarm int // Should always be 2 (medium and large)
	}{
		{
			name:              "No regional filter - returns all instances",
			hotInstances:      nil,
			expectOI2Group:    true,
			expectedOI2Count:  5, // All 5 OI2 sizes (large, xlarge, 2xlarge, 4xlarge, 8xlarge)
			expectedUltraWarm: 2,
		},
		{
			name:              "Empty hot instances - no OI2 available",
			hotInstances:      map[string]price.InstanceUnit{},
			expectOI2Group:    false,
			expectedOI2Count:  0,
			expectedUltraWarm: 2,
		},
		{
			name: "Only some OI2 available",
			hotInstances: map[string]price.InstanceUnit{
				"oi2.xlarge.search":  {InstanceType: "oi2.xlarge.search", CPU: 4},
				"oi2.4xlarge.search": {InstanceType: "oi2.4xlarge.search", CPU: 16},
			},
			expectOI2Group:    true,
			expectedOI2Count:  2, // Only xlarge and 4xlarge
			expectedUltraWarm: 2,
		},
		{
			name: "All OI2 available",
			hotInstances: map[string]price.InstanceUnit{
				"oi2.large.search":   {InstanceType: "oi2.large.search", CPU: 2},
				"oi2.xlarge.search":  {InstanceType: "oi2.xlarge.search", CPU: 4},
				"oi2.2xlarge.search": {InstanceType: "oi2.2xlarge.search", CPU: 8},
				"oi2.4xlarge.search": {InstanceType: "oi2.4xlarge.search", CPU: 16},
				"oi2.8xlarge.search": {InstanceType: "oi2.8xlarge.search", CPU: 32},
			},
			expectOI2Group:    true,
			expectedOI2Count:  5, // All 5 OI2 sizes provided in hotInstances
			expectedUltraWarm: 2,
		},
		{
			name: "Other instance types but no OI2",
			hotInstances: map[string]price.InstanceUnit{
				"r6g.xlarge.search":  {InstanceType: "r6g.xlarge.search", CPU: 4},
				"r6g.2xlarge.search": {InstanceType: "r6g.2xlarge.search", CPU: 8},
			},
			expectOI2Group:    false,
			expectedOI2Count:  0,
			expectedUltraWarm: 2,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			response := GetWarmInstanceTypesForRegion(tt.hotInstances)

			// Check that we always have at least the UltraWarm group
			assert.GreaterOrEqual(t, len(response.WarmInstanceTypes), 1, "Should always have at least UltraWarm group")

			// Find UltraWarm and OI2 groups
			var ultraWarmOptions, oi2Options []WarmInstanceType
			for _, group := range response.WarmInstanceTypes {
				if group.Label == "UltraWarm (Read-Only)" {
					ultraWarmOptions = group.Options
				} else if group.Label == "Writable Warm (OI2) Requires O-series hot nodes" {
					oi2Options = group.Options
				}
			}

			// Verify UltraWarm options
			assert.Equal(t, tt.expectedUltraWarm, len(ultraWarmOptions), "UltraWarm options count mismatch")

			// Verify OI2 options
			if tt.expectOI2Group {
				assert.Equal(t, tt.expectedOI2Count, len(oi2Options), "OI2 options count mismatch")
			} else {
				assert.Equal(t, 0, len(oi2Options), "Should not have OI2 options")
			}
		})
	}
}
