// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package commons

import (
	"math"
	"testing"
)

func TestGetCompressionMultiplier_NoCompression(t *testing.T) {
	// When no compression is enabled, multiplier should be 1.0
	multiplier := GetCompressionMultiplier(false, false, TimeSeriesCompressionRatios)
	if multiplier != 1.0 {
		t.Errorf("Expected multiplier 1.0 when no compression enabled, got %f", multiplier)
	}
}

func TestGetCompressionMultiplier_DerivedSourceOnly_TimeSeries(t *testing.T) {
	// Derived source only should reduce storage by 30% for timeseries
	multiplier := GetCompressionMultiplier(true, false, TimeSeriesCompressionRatios)
	expected := 0.70 // 1 - 0.30
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for derived source only, got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_ZstdOnly_TimeSeries(t *testing.T) {
	// ZSTD only should reduce storage by 20% for timeseries
	multiplier := GetCompressionMultiplier(false, true, TimeSeriesCompressionRatios)
	expected := 0.80 // 1 - 0.20
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for ZSTD only, got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_BothCompressions_TimeSeries(t *testing.T) {
	// Both compressions should be multiplicative: (1-0.30) * (1-0.20) = 0.56
	multiplier := GetCompressionMultiplier(true, true, TimeSeriesCompressionRatios)
	expected := 0.56 // 0.70 * 0.80
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for both compressions, got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_DerivedSourceOnly_Search(t *testing.T) {
	// Derived source only should reduce storage by 25% for search
	multiplier := GetCompressionMultiplier(true, false, SearchCompressionRatios)
	expected := 0.75 // 1 - 0.25
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for derived source only (search), got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_ZstdOnly_Search(t *testing.T) {
	// ZSTD only should reduce storage by 15% for search
	multiplier := GetCompressionMultiplier(false, true, SearchCompressionRatios)
	expected := 0.85 // 1 - 0.15
	if math.Abs(multiplier-expected) > 0.001 {
		t.Errorf("Expected multiplier %f for ZSTD only (search), got %f", expected, multiplier)
	}
}

func TestGetCompressionMultiplier_BothCompressions_Search(t *testing.T) {
	// Both compressions should be multiplicative: (1-0.25) * (1-0.15) = 0.6375
	multiplier := GetCompressionMultiplier(true, true, SearchCompressionRatios)
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
			multiplier := GetCompressionMultiplier(tc.derivedSource, tc.zstd, VectorCompressionRatios)
			if math.Abs(multiplier-tc.expected) > 0.001 {
				t.Errorf("Expected multiplier %f for vector (derivedSource=%v, zstd=%v), got %f",
					tc.expected, tc.derivedSource, tc.zstd, multiplier)
			}
		})
	}
}
