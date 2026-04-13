// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// --- Ingest.Normalize tests ---

func TestIngestNormalizeMaxLessThanMin(t *testing.T) {
	i := Ingest{MinIndexingRate: 10, MaxIndexingRate: 5}
	i.Normalize()
	assert.Equal(t, 10.0, i.MaxIndexingRate, "MaxIndexingRate should be raised to MinIndexingRate")
}

func TestIngestNormalizeMaxGreaterThanMin(t *testing.T) {
	i := Ingest{MinIndexingRate: 5, MaxIndexingRate: 10}
	i.Normalize()
	assert.Equal(t, 10.0, i.MaxIndexingRate, "MaxIndexingRate should remain unchanged")
}

func TestIngestNormalizeBothZero(t *testing.T) {
	i := Ingest{}
	i.Normalize()
	assert.Equal(t, 0.0, i.MaxIndexingRate)
	assert.Equal(t, 0.0, i.MinIndexingRate)
}

// --- Search.Normalize tests ---

func TestSearchNormalizeMaxLessThanMin(t *testing.T) {
	s := Search{MinQueryRate: 100, MaxQueryRate: 50}
	s.Normalize()
	assert.Equal(t, int64(100), s.MaxQueryRate, "MaxQueryRate should be raised to MinQueryRate")
}

func TestSearchNormalizeMaxGreaterThanMin(t *testing.T) {
	s := Search{MinQueryRate: 50, MaxQueryRate: 100}
	s.Normalize()
	assert.Equal(t, int64(100), s.MaxQueryRate, "MaxQueryRate should remain unchanged")
}

// --- TimeSeries.Normalize tests ---

func TestTimeSeriesNormalizeDefaults(t *testing.T) {
	ts := TimeSeries{DaysInHot: 0, DaysInWarm: -1, MinQueryRate: 100, MaxQueryRate: 50}
	ts.Normalize()

	assert.Equal(t, 1, ts.DaysInHot, "DaysInHot should default to 1")
	assert.Equal(t, 0, ts.DaysInWarm, "DaysInWarm should default to 0 when negative")
	assert.Equal(t, int64(100), ts.MaxQueryRate, "MaxQueryRate should be raised to MinQueryRate")
}

func TestTimeSeriesNormalizeValidValues(t *testing.T) {
	ts := TimeSeries{DaysInHot: 7, DaysInWarm: 30, MinQueryRate: 50, MaxQueryRate: 200}
	ts.Normalize()

	assert.Equal(t, 7, ts.DaysInHot)
	assert.Equal(t, 30, ts.DaysInWarm)
	assert.Equal(t, int64(200), ts.MaxQueryRate)
}

// --- Vector.Normalize tests ---

func TestVectorNormalizeAllDefaults(t *testing.T) {
	v := Vector{}
	v.Normalize()

	assert.Equal(t, int64(1000), v.IncrementHint, "IncrementHint should default to 1000")
	assert.Equal(t, 16, v.MaxEdges, "MaxEdges should default to 16")
	assert.Equal(t, "hnsw", v.VectorEngineType, "VectorEngineType should default to hnsw")
	assert.Equal(t, 8, v.SubVectors, "SubVectors should default to 8")
	assert.Equal(t, 4, v.NList, "NList should default to 4")
	assert.Equal(t, 8, v.CodeSize, "CodeSize should default to 8")
}

func TestVectorNormalizeIncrementHintBelowMinimum(t *testing.T) {
	v := Vector{IncrementHint: 500, VectorEngineType: "hnsw", MaxEdges: 16, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(1000), v.IncrementHint)
}

func TestVectorNormalizeIncrementHintAboveMinimum(t *testing.T) {
	v := Vector{IncrementHint: 5000, VectorEngineType: "hnsw", MaxEdges: 16, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(5000), v.IncrementHint, "should keep values above 1000")
}

func TestVectorNormalizeReplicasProd(t *testing.T) {
	v := Vector{Config: "prod", VectorEngineType: "hnsw", MaxEdges: 16, IncrementHint: 1000, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(1), v.Replicas, "prod config should set replicas to 1")
}

func TestVectorNormalizeReplicasDev(t *testing.T) {
	v := Vector{Config: "dev", VectorEngineType: "hnsw", MaxEdges: 16, IncrementHint: 1000, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(0), v.Replicas, "dev config should set replicas to 0")
}

func TestVectorNormalizeNegativeReplicas(t *testing.T) {
	v := Vector{Replicas: -5, VectorEngineType: "hnsw", MaxEdges: 16, IncrementHint: 1000, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(1), v.Replicas, "negative replicas should normalize to 1")
}

// --- Vector.validateOnDiskMode tests ---

func TestVectorValidateOnDiskModeDisabled(t *testing.T) {
	v := Vector{OnDisk: false, CompressionLevel: 0, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.False(t, v.OnDisk)
	assert.Equal(t, 32, v.CompressionLevel, "should default to 32 when OnDisk is false")
}

func TestVectorValidateOnDiskModeDisabledWithExistingLevel(t *testing.T) {
	v := Vector{OnDisk: false, CompressionLevel: 8, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.Equal(t, 8, v.CompressionLevel, "should keep existing compression level")
}

func TestVectorValidateOnDiskModeEnabledHNSW(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 16, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk, "HNSW should support on-disk mode")
	assert.Equal(t, 16, v.CompressionLevel)
}

func TestVectorValidateOnDiskModeEnabledIVF(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 4, VectorEngineType: "ivf"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk, "IVF should support on-disk mode")
	assert.Equal(t, 4, v.CompressionLevel)
}

func TestVectorValidateOnDiskModeEnabledNMSLIB(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 8, VectorEngineType: "nmslib"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk, "NMSLIB should support on-disk mode")
	assert.Equal(t, 8, v.CompressionLevel)
}

func TestVectorValidateOnDiskModeUnsupportedEngine(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 8, VectorEngineType: "hnswfp16"}
	v.validateOnDiskMode()
	assert.False(t, v.OnDisk, "hnswfp16 should not support on-disk mode")
	assert.Equal(t, 32, v.CompressionLevel, "should reset to default")
}

func TestVectorValidateOnDiskModeInvalidCompressionLevel(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 7, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk)
	assert.Equal(t, 32, v.CompressionLevel, "invalid level should reset to 32")
}

func TestVectorValidateOnDiskModeDefaultCompressionLevel(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 0, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk)
	assert.Equal(t, 32, v.CompressionLevel, "zero compression should default to 32")
}

// --- isValidCompressionLevel tests ---

func TestIsValidCompressionLevel(t *testing.T) {
	tests := []struct {
		level int
		valid bool
	}{
		{2, true}, {4, true}, {8, true}, {16, true}, {32, true},
		{0, false}, {1, false}, {3, false}, {7, false}, {64, false},
	}
	for _, tt := range tests {
		assert.Equal(t, tt.valid, isValidCompressionLevel(tt.level), "level %d", tt.level)
	}
}

// --- isUncompressedEngine tests ---

func TestIsUncompressedEngine(t *testing.T) {
	tests := []struct {
		engine string
		valid  bool
	}{
		{"hnsw", true}, {"HNSW", true}, {"Hnsw", true},
		{"nmslib", true}, {"NMSLIB", true},
		{"ivf", true}, {"IVF", true},
		{"hnswfp16", false}, {"hnswbv", false}, {"ivfpq", false}, {"hnswpq", false}, {"ivfbv", false},
		{"", false},
	}
	for _, tt := range tests {
		assert.Equal(t, tt.valid, isUncompressedEngine(tt.engine), "engine %q", tt.engine)
	}
}

// --- EstimateRequest.Normalize tests ---

func TestEstimateRequestNormalizeDefaultRedundancy(t *testing.T) {
	er := EstimateRequest{Region: "us-east-1"}
	er.Normalize()

	assert.NotNil(t, er.Redundancy, "Redundancy should be set to non-nil")
	assert.True(t, *er.Redundancy, "Default redundancy should be true")
}

func TestEstimateRequestNormalizeExplicitRedundancy(t *testing.T) {
	redundancyOff := false
	er := EstimateRequest{Region: "us-east-1", Redundancy: &redundancyOff}
	er.Normalize()

	assert.False(t, *er.Redundancy, "Explicit false should be preserved")
}

func TestEstimateRequestNormalizeWithSearch(t *testing.T) {
	er := EstimateRequest{
		Region: "us-east-1",
		Search: &Search{MinQueryRate: 100, MaxQueryRate: 50},
	}
	er.Normalize()

	assert.Equal(t, int64(100), er.Search.MaxQueryRate, "Search should be normalized")
}

func TestEstimateRequestNormalizeWithTimeSeries(t *testing.T) {
	er := EstimateRequest{
		Region:     "us-east-1",
		TimeSeries: &TimeSeries{DaysInHot: 0, MinQueryRate: 10, MaxQueryRate: 5},
	}
	er.Normalize()

	assert.Equal(t, 1, er.TimeSeries.DaysInHot, "TimeSeries should be normalized")
	assert.Equal(t, int64(10), er.TimeSeries.MaxQueryRate)
}

func TestEstimateRequestNormalizeWithVector(t *testing.T) {
	er := EstimateRequest{
		Region: "us-east-1",
		Vector: &Vector{IncrementHint: 100},
	}
	er.Normalize()

	assert.Equal(t, int64(1000), er.Vector.IncrementHint, "Vector should be normalized")
	assert.Equal(t, "hnsw", er.Vector.VectorEngineType)
}

// --- vectorUtils pure functions tests ---

func TestBytesToGb(t *testing.T) {
	// 1 GB = 1073741824 bytes
	assert.InDelta(t, 1.0, BytesToGb(1073741824), 0.001)
	assert.InDelta(t, 0.0, BytesToGb(0), 0.001)
}

func TestVectorMemoryForDimensionsBytes(t *testing.T) {
	// For dimensions=768, M=16: 1.1 * (4*768 + 8*16) = 1.1 * 3200 ≈ 3520 (ceil handles float precision)
	result := VectorMemoryForDimensionsBytes(768)
	assert.Greater(t, result, int64(0))
	assert.InDelta(t, 3520, result, 2, "should be approximately 3520 bytes")

	// For dimensions=0: 1.1 * (0 + 128) = 140.8 → ceil = 141
	result = VectorMemoryForDimensionsBytes(0)
	assert.Equal(t, int64(141), result)
}

func TestVectorGetRequiredMemoryInBytesHNSW(t *testing.T) {
	v := Vector{
		VectorEngineType: "hnsw",
		DimensionsCount:  768,
		VectorCount:      1000,
		MaxEdges:         16,
		Replicas:         0,
	}
	mem, calcString, err := v.GetRequiredMemoryInBytes()
	assert.NoError(t, err)
	assert.Greater(t, mem, int64(0))
	assert.NotEmpty(t, calcString)
}

func TestVectorGetRequiredMemoryInBytesHNSWWithOnDisk(t *testing.T) {
	v := Vector{
		VectorEngineType: "hnsw",
		DimensionsCount:  768,
		VectorCount:      1000,
		MaxEdges:         16,
		Replicas:         0,
		OnDisk:           true,
		CompressionLevel: 32,
	}
	memCompressed, _, err := v.GetRequiredMemoryInBytes()
	assert.NoError(t, err)

	v.OnDisk = false
	memFull, _, err := v.GetRequiredMemoryInBytes()
	assert.NoError(t, err)

	assert.Less(t, memCompressed, memFull, "on-disk compressed should use less memory")
}

func TestVectorGetRequiredMemoryInBytesUnsupportedEngine(t *testing.T) {
	v := Vector{VectorEngineType: "unknown"}
	_, _, err := v.GetRequiredMemoryInBytes()
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "engine type is not supported")
}

func TestVectorGetRequiredMemoryInBytesAllEngines(t *testing.T) {
	tests := []struct {
		engine string
	}{
		{"hnsw"}, {"nsmlib"}, {"hnswfp16"}, {"hnswbv"},
		{"ivf"}, {"ivfbv"}, {"ivfpq"}, {"hnswpq"},
	}
	for _, tt := range tests {
		t.Run(tt.engine, func(t *testing.T) {
			v := Vector{
				VectorEngineType: tt.engine,
				DimensionsCount:  128,
				VectorCount:      100,
				MaxEdges:         16,
				Replicas:         0,
				SubVectors:       8,
				NList:            4,
				CodeSize:         8,
			}
			mem, calcString, err := v.GetRequiredMemoryInBytes()
			assert.NoError(t, err)
			assert.Greater(t, mem, int64(0), "engine %s should produce positive memory", tt.engine)
			assert.NotEmpty(t, calcString, "engine %s should produce formula string", tt.engine)
		})
	}
}

func TestVectorGetRequiredMemoryInGB(t *testing.T) {
	v := Vector{
		VectorEngineType: "hnsw",
		DimensionsCount:  768,
		VectorCount:      1000000,
		MaxEdges:         16,
		Replicas:         0,
	}
	gb, _, err := v.GetRequiredMemoryInGB()
	assert.NoError(t, err)
	assert.Greater(t, gb, 0.0)
}

func TestVectorCalculateRequiredMemory(t *testing.T) {
	v := Vector{
		VectorEngineType: "hnsw",
		DimensionsCount:  768,
		VectorCount:      1000000,
		MaxEdges:         16,
		Replicas:         0,
	}
	mem, err := v.CalculateRequiredMemory()
	assert.NoError(t, err)
	assert.Greater(t, mem, 0.0)
	assert.Equal(t, mem, v.VectorGraphSizeInMemory, "should update struct field")
}
