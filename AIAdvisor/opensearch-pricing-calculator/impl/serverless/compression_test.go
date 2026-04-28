// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"math"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/commons"
	"testing"
)

func TestGetCompressionMultiplier_NoCompression(t *testing.T) {
	// When no compression is enabled, multiplier should be 1.0
	multiplier := commons.GetCompressionMultiplier(false, false, commons.TimeSeriesCompressionRatios)
	if multiplier != 1.0 {
		t.Errorf("Expected multiplier 1.0 when no compression enabled, got %f", multiplier)
	}
}

func TestGetCompressionMultiplier_DerivedSourceOnly_TimeSeries(t *testing.T) {
	// Derived source only should reduce storage by 30% for timeseries
	multiplier := commons.GetCompressionMultiplier(true, false, commons.TimeSeriesCompressionRatios)
	expected := 0.70 // 1 - 0.30
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for derived source only, got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_ZstdOnly_TimeSeries(t *testing.T) {
	// ZSTD only should reduce storage by 20% for timeseries
	multiplier := commons.GetCompressionMultiplier(false, true, commons.TimeSeriesCompressionRatios)
	expected := 0.80 // 1 - 0.20
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for ZSTD only, got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_BothCompressions_TimeSeries(t *testing.T) {
	// Both compressions should be multiplicative: (1-0.30) * (1-0.20) = 0.56
	multiplier := commons.GetCompressionMultiplier(true, true, commons.TimeSeriesCompressionRatios)
	expected := 0.56 // 0.70 * 0.80
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for both compressions, got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_DerivedSourceOnly_Search(t *testing.T) {
	// Derived source only should reduce storage by 25% for search
	multiplier := commons.GetCompressionMultiplier(true, false, commons.SearchCompressionRatios)
	expected := 0.75 // 1 - 0.25
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for derived source only (search), got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_ZstdOnly_Search(t *testing.T) {
	// ZSTD only should reduce storage by 15% for search
	multiplier := commons.GetCompressionMultiplier(false, true, commons.SearchCompressionRatios)
	expected := 0.85 // 1 - 0.15
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for ZSTD only (search), got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_BothCompressions_Search(t *testing.T) {
	// Both compressions should be multiplicative: (1-0.25) * (1-0.15) = 0.6375
	multiplier := commons.GetCompressionMultiplier(true, true, commons.SearchCompressionRatios)
	expected := 0.6375 // 0.75 * 0.85
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for both compressions (search), got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_VectorRatios(t *testing.T) {
	// Vector uses TimeSeries ratios for non-vector data storage
	// This applies compression to document/metadata, not the vector graph
	tests := []struct {
		name          string
		derivedSource bool
		zstd          bool
		expected      float64
	}{
		{"no compression", false, false, 1.0},
		{"derived source only", true, false, 0.70},
		{"zstd only", false, true, 0.80},
		{"both compressions", true, true, 0.56},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			multiplier := commons.GetCompressionMultiplier(tc.derivedSource, tc.zstd, commons.VectorCompressionRatios)
			if math.Abs(multiplier-tc.expected) > 0.001 {
				t.Errorf("Expected multiplier %f for vector (derivedSource=%v, zstd=%v), got %f",
					tc.expected, tc.derivedSource, tc.zstd, multiplier)
			}
		})
	}
}

func TestTimeSeries_CompressionApplied(t *testing.T) {
	// Test that compression is applied to TimeSeries storage calculations
	baseTimeSeries := TimeSeries{
		DailyIndexSize:  100, // 100 GB per day
		DaysInHot:       7,
		DaysInWarm:      30,
		DerivedSource:   false,
		ZstdCompression: false,
	}
	baseTimeSeries.calculateV2()
	baseHotSize := baseTimeSeries.HotIndexSize
	baseWarmSize := baseTimeSeries.WarmIndexSize

	// Test with derived source only
	compressedTimeSeries := TimeSeries{
		DailyIndexSize:  100,
		DaysInHot:       7,
		DaysInWarm:      30,
		DerivedSource:   true,
		ZstdCompression: false,
	}
	compressedTimeSeries.calculateV2()

	// Hot size should be reduced by ~30%
	expectedHotSize := baseHotSize * 0.70
	if math.Abs(compressedTimeSeries.HotIndexSize-expectedHotSize) > 0.1 {
		t.Errorf("Expected HotIndexSize ~%f with derived source, got %f",
			expectedHotSize, compressedTimeSeries.HotIndexSize)
	}

	// Warm size should be reduced by ~30%
	expectedWarmSize := baseWarmSize * 0.70
	if math.Abs(compressedTimeSeries.WarmIndexSize-expectedWarmSize) > 0.1 {
		t.Errorf("Expected WarmIndexSize ~%f with derived source, got %f",
			expectedWarmSize, compressedTimeSeries.WarmIndexSize)
	}
}

func TestTimeSeries_BothCompressionsApplied(t *testing.T) {
	// Test that both compressions are applied multiplicatively
	baseTimeSeries := TimeSeries{
		DailyIndexSize:  100,
		DaysInHot:       7,
		DaysInWarm:      0,
		DerivedSource:   false,
		ZstdCompression: false,
	}
	baseTimeSeries.calculateV2()
	baseHotSize := baseTimeSeries.HotIndexSize

	bothCompressedTimeSeries := TimeSeries{
		DailyIndexSize:  100,
		DaysInHot:       7,
		DaysInWarm:      0,
		DerivedSource:   true,
		ZstdCompression: true,
	}
	bothCompressedTimeSeries.calculateV2()

	// Should be reduced by ~44% (0.70 * 0.80 = 0.56)
	expectedHotSize := baseHotSize * 0.56
	if math.Abs(bothCompressedTimeSeries.HotIndexSize-expectedHotSize) > 0.1 {
		t.Errorf("Expected HotIndexSize ~%f with both compressions, got %f",
			expectedHotSize, bothCompressedTimeSeries.HotIndexSize)
	}
}

func TestSearch_CompressionApplied(t *testing.T) {
	// Test that compression is applied to Search storage calculations
	baseSearch := Search{
		CollectionSize:  500, // 500 GB
		DerivedSource:   false,
		ZstdCompression: false,
	}
	baseSearch.calculate()
	baseMinOCU := baseSearch.MinOCU

	// Test with both compressions
	compressedSearch := Search{
		CollectionSize:  500,
		DerivedSource:   true,
		ZstdCompression: true,
	}
	compressedSearch.calculate()

	// Effective collection size should be reduced, potentially affecting OCU
	// For 500 GB: 500 * 0.6375 = 318.75 GB
	// This should result in lower or equal OCU requirements
	if compressedSearch.MinOCU > baseMinOCU {
		t.Errorf("Expected MinOCU to be <= %f with compression, got %f",
			baseMinOCU, compressedSearch.MinOCU)
	}
}
