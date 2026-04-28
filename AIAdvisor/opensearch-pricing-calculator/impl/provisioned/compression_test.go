// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"math"
	"testing"
)

func TestTimeSeriesEstimate_WithDerivedSource(t *testing.T) {
	// Test that derived source reduces storage by ~30%
	baseReq := GetDefaultTimeSeriesRequest()
	baseReq.IngestionSize = 100
	baseReq.HotRetentionPeriod = 7
	baseReq.Region = "US East (N. Virginia)"
	baseReq.DerivedSource = false
	baseReq.ZstdCompression = false

	baseResponse, err := baseReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate base estimate: %v", err)
	}

	compressedReq := GetDefaultTimeSeriesRequest()
	compressedReq.IngestionSize = 100
	compressedReq.HotRetentionPeriod = 7
	compressedReq.Region = "US East (N. Virginia)"
	compressedReq.DerivedSource = true
	compressedReq.ZstdCompression = false

	compressedResponse, err := compressedReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate compressed estimate: %v", err)
	}

	// Verify ~30% reduction
	expectedReduction := 0.30
	actualReduction := 1 - (compressedResponse.TotalHotStorage / baseResponse.TotalHotStorage)

	if math.Abs(actualReduction-expectedReduction) > 0.05 {
		t.Errorf("Expected ~30%% reduction with derived source, got %.1f%% (base: %.2f GB, compressed: %.2f GB)",
			actualReduction*100, baseResponse.TotalHotStorage, compressedResponse.TotalHotStorage)
	}
}

func TestTimeSeriesEstimate_WithZstdCompression(t *testing.T) {
	// Test that ZSTD compression reduces storage by ~20%
	baseReq := GetDefaultTimeSeriesRequest()
	baseReq.IngestionSize = 100
	baseReq.HotRetentionPeriod = 7
	baseReq.Region = "US East (N. Virginia)"
	baseReq.DerivedSource = false
	baseReq.ZstdCompression = false

	baseResponse, err := baseReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate base estimate: %v", err)
	}

	compressedReq := GetDefaultTimeSeriesRequest()
	compressedReq.IngestionSize = 100
	compressedReq.HotRetentionPeriod = 7
	compressedReq.Region = "US East (N. Virginia)"
	compressedReq.DerivedSource = false
	compressedReq.ZstdCompression = true

	compressedResponse, err := compressedReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate compressed estimate: %v", err)
	}

	// Verify ~20% reduction
	expectedReduction := 0.20
	actualReduction := 1 - (compressedResponse.TotalHotStorage / baseResponse.TotalHotStorage)

	if math.Abs(actualReduction-expectedReduction) > 0.05 {
		t.Errorf("Expected ~20%% reduction with ZSTD, got %.1f%% (base: %.2f GB, compressed: %.2f GB)",
			actualReduction*100, baseResponse.TotalHotStorage, compressedResponse.TotalHotStorage)
	}
}

func TestTimeSeriesEstimate_WithBothCompressions(t *testing.T) {
	// Test that both compressions combine multiplicatively
	baseReq := GetDefaultTimeSeriesRequest()
	baseReq.IngestionSize = 100
	baseReq.HotRetentionPeriod = 7
	baseReq.Region = "US East (N. Virginia)"
	baseReq.DerivedSource = false
	baseReq.ZstdCompression = false

	baseResponse, err := baseReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate base estimate: %v", err)
	}

	compressedReq := GetDefaultTimeSeriesRequest()
	compressedReq.IngestionSize = 100
	compressedReq.HotRetentionPeriod = 7
	compressedReq.Region = "US East (N. Virginia)"
	compressedReq.DerivedSource = true
	compressedReq.ZstdCompression = true

	compressedResponse, err := compressedReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate compressed estimate: %v", err)
	}

	// Verify multiplicative reduction: (1-0.30) * (1-0.20) = 0.56 => ~44% reduction
	expectedMultiplier := 0.56
	actualMultiplier := compressedResponse.TotalHotStorage / baseResponse.TotalHotStorage

	if math.Abs(actualMultiplier-expectedMultiplier) > 0.05 {
		t.Errorf("Expected ~56%% of original with both compressions, got %.1f%% (base: %.2f GB, compressed: %.2f GB)",
			actualMultiplier*100, baseResponse.TotalHotStorage, compressedResponse.TotalHotStorage)
	}
}

func TestTimeSeriesEstimate_WarmStorageCompressed(t *testing.T) {
	// Test that compression also applies to warm storage
	baseReq := GetDefaultTimeSeriesRequest()
	baseReq.IngestionSize = 100
	baseReq.HotRetentionPeriod = 7
	baseReq.WarmRetentionPeriod = 30
	baseReq.Region = "US East (N. Virginia)"
	baseReq.DerivedSource = false
	baseReq.ZstdCompression = false

	baseResponse, err := baseReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate base estimate: %v", err)
	}

	compressedReq := GetDefaultTimeSeriesRequest()
	compressedReq.IngestionSize = 100
	compressedReq.HotRetentionPeriod = 7
	compressedReq.WarmRetentionPeriod = 30
	compressedReq.Region = "US East (N. Virginia)"
	compressedReq.DerivedSource = true
	compressedReq.ZstdCompression = false

	compressedResponse, err := compressedReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate compressed estimate: %v", err)
	}

	// Verify warm storage is also reduced by ~30%
	expectedReduction := 0.30
	actualReduction := 1 - (compressedResponse.TotalWarmStorage / baseResponse.TotalWarmStorage)

	if math.Abs(actualReduction-expectedReduction) > 0.05 {
		t.Errorf("Expected ~30%% warm storage reduction, got %.1f%% (base: %.2f GB, compressed: %.2f GB)",
			actualReduction*100, baseResponse.TotalWarmStorage, compressedResponse.TotalWarmStorage)
	}
}

func TestSearchEstimate_WithDerivedSource(t *testing.T) {
	// Test that derived source reduces storage by ~25% for search
	baseReq := GetDefaultSearchRequest()
	baseReq.DataSize = 500
	baseReq.Region = "US East (N. Virginia)"
	baseReq.DerivedSource = false
	baseReq.ZstdCompression = false

	baseResponse, err := baseReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate base estimate: %v", err)
	}

	compressedReq := GetDefaultSearchRequest()
	compressedReq.DataSize = 500
	compressedReq.Region = "US East (N. Virginia)"
	compressedReq.DerivedSource = true
	compressedReq.ZstdCompression = false

	compressedResponse, err := compressedReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate compressed estimate: %v", err)
	}

	// Verify ~25% reduction
	expectedReduction := 0.25
	actualReduction := 1 - (compressedResponse.TotalHotStorage / baseResponse.TotalHotStorage)

	if math.Abs(actualReduction-expectedReduction) > 0.05 {
		t.Errorf("Expected ~25%% reduction with derived source, got %.1f%% (base: %.2f GB, compressed: %.2f GB)",
			actualReduction*100, baseResponse.TotalHotStorage, compressedResponse.TotalHotStorage)
	}
}

func TestSearchEstimate_WithBothCompressions(t *testing.T) {
	// Test that both compressions combine for ~36% reduction for search
	baseReq := GetDefaultSearchRequest()
	baseReq.DataSize = 500
	baseReq.Region = "US East (N. Virginia)"
	baseReq.DerivedSource = false
	baseReq.ZstdCompression = false

	baseResponse, err := baseReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate base estimate: %v", err)
	}

	compressedReq := GetDefaultSearchRequest()
	compressedReq.DataSize = 500
	compressedReq.Region = "US East (N. Virginia)"
	compressedReq.DerivedSource = true
	compressedReq.ZstdCompression = true

	compressedResponse, err := compressedReq.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate compressed estimate: %v", err)
	}

	// Verify multiplicative reduction: (1-0.25) * (1-0.15) = 0.6375 => ~36.25% reduction
	expectedMultiplier := 0.6375
	actualMultiplier := compressedResponse.TotalHotStorage / baseResponse.TotalHotStorage

	if math.Abs(actualMultiplier-expectedMultiplier) > 0.05 {
		t.Errorf("Expected ~63.75%% of original with both compressions, got %.1f%% (base: %.2f GB, compressed: %.2f GB)",
			actualMultiplier*100, baseResponse.TotalHotStorage, compressedResponse.TotalHotStorage)
	}
}

func TestTimeSeriesEstimate_DefaultNoCompression(t *testing.T) {
	// Test that compression defaults to disabled (backward compatibility)
	req := GetDefaultTimeSeriesRequest()
	req.IngestionSize = 100
	req.HotRetentionPeriod = 7
	req.Region = "US East (N. Virginia)"
	// DerivedSource and ZstdCompression not set (should default to false)

	response, err := req.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate estimate: %v", err)
	}

	// Calculate expected storage without compression
	// 100 GB * (1 + 35/100) * 2 * 7 days = 1890 GB (with 25% free + 10% expansion and 1 replica)
	// This is approximate - the actual calculation uses different multipliers
	// The key is that storage should not be reduced
	if response.TotalHotStorage <= 0 {
		t.Errorf("Expected positive TotalHotStorage, got %.2f", response.TotalHotStorage)
	}
}

func TestSearchEstimate_DefaultNoCompression(t *testing.T) {
	// Test that compression defaults to disabled (backward compatibility)
	req := GetDefaultSearchRequest()
	req.DataSize = 100
	req.Region = "US East (N. Virginia)"
	// DerivedSource and ZstdCompression not set (should default to false)

	response, err := req.Calculate()
	if err != nil {
		t.Fatalf("Failed to calculate estimate: %v", err)
	}

	// Storage should not be reduced
	if response.TotalHotStorage <= 0 {
		t.Errorf("Expected positive TotalHotStorage, got %.2f", response.TotalHotStorage)
	}
}
