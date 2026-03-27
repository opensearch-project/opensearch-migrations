// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package commons

// CompressionRatios defines the storage reduction ratios for derived source and ZSTD compression
type CompressionRatios struct {
	DerivedSource float64
	ZSTD          float64
}

// Workload-specific compression ratios
// These values represent the percentage of storage reduction when each compression is enabled
var (
	// TimeSeriesCompressionRatios defines compression ratios for time-series/log analytics workloads
	// Derived source provides ~30% reduction, ZSTD provides ~20% reduction
	TimeSeriesCompressionRatios = CompressionRatios{DerivedSource: 0.30, ZSTD: 0.20}

	// SearchCompressionRatios defines compression ratios for search workloads
	// Derived source provides ~25% reduction, ZSTD provides ~15% reduction
	SearchCompressionRatios = CompressionRatios{DerivedSource: 0.25, ZSTD: 0.15}

	// VectorCompressionRatios defines compression ratios for vector workloads
	// Vector data uses on-disk compression mode instead; these ratios apply to non-vector data
	// Uses same ratios as TimeSeries for document/metadata storage
	VectorCompressionRatios = CompressionRatios{DerivedSource: 0.30, ZSTD: 0.20}
)

// GetCompressionMultiplier calculates the storage multiplier based on enabled compression options.
//
// The multiplier is calculated as: (1 - derivedSourceRatio) * (1 - zstdRatio)
// For example, with derived source (30%) and ZSTD (20%):
// multiplier = 0.70 * 0.80 = 0.56 (44% reduction)
//
// Parameters:
//   - derivedSourceEnabled: whether derived source compression is enabled
//   - zstdEnabled: whether ZSTD compression is enabled
//   - ratios: the compression ratios to use (workload-specific)
//
// Returns a multiplier between 0 and 1 to apply to storage calculations
func GetCompressionMultiplier(derivedSourceEnabled, zstdEnabled bool, ratios CompressionRatios) float64 {
	multiplier := 1.0
	if derivedSourceEnabled && ratios.DerivedSource > 0 {
		multiplier *= 1.0 - ratios.DerivedSource
	}
	if zstdEnabled && ratios.ZSTD > 0 {
		multiplier *= 1.0 - ratios.ZSTD
	}
	return multiplier
}
