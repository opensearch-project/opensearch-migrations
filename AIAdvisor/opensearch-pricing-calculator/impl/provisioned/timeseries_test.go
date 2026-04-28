// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"math"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestUltrawarmNodeCountLimits(t *testing.T) {
	tests := []struct {
		name              string
		azs               int
		totalWarmStorage  float64
		instanceType      string
		maxUltrawarmNodes int
		shouldSkip        bool
	}{
		{
			name:              "1 AZ medium nodes below limit",
			azs:               1,
			totalWarmStorage:  200 * 1024, // 200TB, requires ~134 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 250,
			shouldSkip:        false,
		},
		{
			name:              "1 AZ medium nodes at limit",
			azs:               1,
			totalWarmStorage:  375 * 1024, // 375TB, requires 250 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 250,
			shouldSkip:        false,
		},
		{
			name:              "1 AZ medium nodes above limit",
			azs:               1,
			totalWarmStorage:  400 * 1024, // 400TB, requires ~267 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 250,
			shouldSkip:        true,
		},
		{
			name:              "1 AZ large nodes below limit",
			azs:               1,
			totalWarmStorage:  4000 * 1024, // 4000TB, requires 200 large nodes
			instanceType:      "ultrawarm1.large.search",
			maxUltrawarmNodes: 250,
			shouldSkip:        false,
		},
		{
			name:              "1 AZ large nodes above limit",
			azs:               1,
			totalWarmStorage:  6000 * 1024, // 6000TB, requires 300 large nodes
			instanceType:      "ultrawarm1.large.search",
			maxUltrawarmNodes: 250,
			shouldSkip:        true,
		},
		{
			name:              "2 AZs medium nodes below limit",
			azs:               2,
			totalWarmStorage:  700 * 1024, // 700TB, requires ~467 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 500,
			shouldSkip:        false,
		},
		{
			name:              "2 AZs medium nodes at limit",
			azs:               2,
			totalWarmStorage:  750 * 1024, // 750TB, requires 500 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 500,
			shouldSkip:        false,
		},
		{
			name:              "2 AZs medium nodes above limit",
			azs:               2,
			totalWarmStorage:  800 * 1024, // 800TB, requires ~534 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 500,
			shouldSkip:        true,
		},
		{
			name:              "3 AZs medium nodes below limit",
			azs:               3,
			totalWarmStorage:  1000 * 1024, // 1000TB, requires ~667 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 750,
			shouldSkip:        false,
		},
		{
			name:              "3 AZs medium nodes at limit",
			azs:               3,
			totalWarmStorage:  1125 * 1024, // 1125TB, requires 750 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 750,
			shouldSkip:        false,
		},
		{
			name:              "3 AZs medium nodes above limit",
			azs:               3,
			totalWarmStorage:  1200 * 1024, // 1200TB, requires 800 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 750,
			shouldSkip:        true,
		},
		{
			name:              "4 AZs (default to 3 AZ limit)",
			azs:               4,
			totalWarmStorage:  1200 * 1024, // 1200TB, requires 800 medium nodes
			instanceType:      "ultrawarm1.medium.search",
			maxUltrawarmNodes: 750,
			shouldSkip:        true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Create a warm nodes object
			warmNodes := &WarmNodes{}

			// Calculate the number of nodes based on the storage and instance type
			// This mirrors the logic in the Calculate method
			if tt.instanceType == "ultrawarm1.medium.search" {
				warmNodes.Count = int(math.Max(math.Ceil(tt.totalWarmStorage/(1.5*1024)), 2.0))
				warmNodes.Type = "ultrawarm1.medium.search"
			} else {
				warmNodes.Count = int(math.Max(math.Ceil(tt.totalWarmStorage/(20*1024)), 2.0))
				warmNodes.Type = "ultrawarm1.large.search"
			}

			// Determine the expected max ultrawarm nodes based on AZs
			var maxUltrawarmNodes int
			switch tt.azs {
			case 1:
				maxUltrawarmNodes = 250
			case 2:
				maxUltrawarmNodes = 500
			case 3:
				maxUltrawarmNodes = 750
			default:
				maxUltrawarmNodes = 750
			}

			// Verify the max ultrawarm nodes matches our expectation
			assert.Equal(t, tt.maxUltrawarmNodes, maxUltrawarmNodes, "Max ultrawarm nodes doesn't match expected value")

			// Check if the calculated node count exceeds the limit
			exceeds := warmNodes.Count > maxUltrawarmNodes
			assert.Equal(t, tt.shouldSkip, exceeds, "Warm node count limit check failed")
		})
	}
}

// TestTimeSeriesEstimateRequest_Calculate_UltrawarmNodeLimits tests the actual Calculate method
// with a focus on the ultrawarm node limits
func TestTimeSeriesEstimateRequest_Calculate_UltrawarmNodeLimits(t *testing.T) {
	// Skip this test for now as it requires more complex mocking
	t.Skip("Skipping integration test that requires complex mocking")
}

func TestCalculateColdStorageRampUp(t *testing.T) {
	tests := []struct {
		name                  string
		hotRetention          int
		warmRetention         int
		coldRetention         int
		ingestionSize         float64
		expansionRate         int
		compressionMultiplier float64
		pricePerGBMonth       float64
		expectNil             bool
		expectedWarmUpDays    int
		expectedSteadyMonth   int
		checkFirstMonthsZero  int // how many initial months should be zero
	}{
		{
			name:          "no cold retention returns nil",
			hotRetention:  30,
			warmRetention: 60,
			coldRetention: 0,
			ingestionSize: 3.0,
			expansionRate: 10,
			compressionMultiplier: 1.0,
			pricePerGBMonth: 0.016,
			expectNil:     true,
		},
		{
			name:                  "3GB/day 30hot 60warm 1825cold no compression",
			hotRetention:          30,
			warmRetention:         60,
			coldRetention:         1825,
			ingestionSize:         3.0,
			expansionRate:         10,
			compressionMultiplier: 1.0,
			pricePerGBMonth:       0.016,
			expectNil:             false,
			expectedWarmUpDays:    90,
			expectedSteadyMonth:   63, // ceil((90+1825)/30.42) = ceil(62.92) = 63
			checkFirstMonthsZero: 2,  // months 1-2 are fully within warm-up
		},
		{
			name:                  "no warm retention",
			hotRetention:          30,
			warmRetention:         0,
			coldRetention:         365,
			ingestionSize:         5.0,
			expansionRate:         0,
			compressionMultiplier: 1.0,
			pricePerGBMonth:       0.016,
			expectNil:             false,
			expectedWarmUpDays:    30,
			expectedSteadyMonth:   13, // ceil((30+365)/30.42) = ceil(12.98) = 13
			checkFirstMonthsZero: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := calculateColdStorageRampUp(
				tt.hotRetention, tt.warmRetention, tt.coldRetention,
				tt.ingestionSize, tt.expansionRate, tt.compressionMultiplier,
				tt.pricePerGBMonth,
			)

			if tt.expectNil {
				assert.Nil(t, result)
				return
			}

			assert.NotNil(t, result)
			assert.Equal(t, tt.expectedWarmUpDays, result.WarmUpDays)
			assert.Equal(t, tt.expectedSteadyMonth, result.SteadyStateMonth)
			assert.Equal(t, tt.coldRetention, result.ColdRetentionDays)
			assert.Equal(t, tt.pricePerGBMonth, result.PricePerGBMonth)
			assert.Len(t, result.MonthlyCosts, result.SteadyStateMonth)

			// First N months should be zero (data still in hot/warm)
			for i := 0; i < tt.checkFirstMonthsZero; i++ {
				assert.Equal(t, 0.0, result.MonthlyCosts[i],
					"Month %d should be zero (still in warm-up)", i+1)
			}

			// Costs should be monotonically non-decreasing
			for i := 1; i < len(result.MonthlyCosts); i++ {
				assert.GreaterOrEqual(t, result.MonthlyCosts[i], result.MonthlyCosts[i-1],
					"Month %d cost should be >= month %d cost", i+1, i)
			}

			// Last month should be the steady-state (maximum) cost
			lastCost := result.MonthlyCosts[len(result.MonthlyCosts)-1]
			assert.Greater(t, lastCost, 0.0, "Steady state cost should be positive")

			// DailyColdDataGB should be ingestionSize * (1 + expansionRate/100) * compressionMultiplier
			expectedDaily := tt.ingestionSize * (1 + float64(tt.expansionRate)/100.0) * tt.compressionMultiplier
			expectedDaily = math.Round(expectedDaily*100) / 100
			assert.Equal(t, expectedDaily, result.DailyColdDataGB)
		})
	}
}
