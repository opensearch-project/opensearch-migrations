// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// --- Ingest.Normalize tests ---

func TestIngest_Normalize_MaxLessThanMin(t *testing.T) {
	i := Ingest{MinIndexingRate: 10, MaxIndexingRate: 5}
	i.Normalize()
	assert.Equal(t, 10.0, i.MaxIndexingRate, "MaxIndexingRate should be raised to MinIndexingRate")
}

func TestIngest_Normalize_MaxGreaterThanMin(t *testing.T) {
	i := Ingest{MinIndexingRate: 5, MaxIndexingRate: 10}
	i.Normalize()
	assert.Equal(t, 10.0, i.MaxIndexingRate, "MaxIndexingRate should remain unchanged")
}

func TestIngest_Normalize_BothZero(t *testing.T) {
	i := Ingest{}
	i.Normalize()
	assert.Equal(t, 0.0, i.MaxIndexingRate)
	assert.Equal(t, 0.0, i.MinIndexingRate)
}

// --- Search.Normalize tests ---

func TestSearch_Normalize_MaxLessThanMin(t *testing.T) {
	s := Search{MinQueryRate: 100, MaxQueryRate: 50}
	s.Normalize()
	assert.Equal(t, int64(100), s.MaxQueryRate, "MaxQueryRate should be raised to MinQueryRate")
}

func TestSearch_Normalize_MaxGreaterThanMin(t *testing.T) {
	s := Search{MinQueryRate: 50, MaxQueryRate: 100}
	s.Normalize()
	assert.Equal(t, int64(100), s.MaxQueryRate, "MaxQueryRate should remain unchanged")
}

// --- TimeSeries.Normalize tests ---

func TestTimeSeries_Normalize_Defaults(t *testing.T) {
	ts := TimeSeries{DaysInHot: 0, DaysInWarm: -1, MinQueryRate: 100, MaxQueryRate: 50}
	ts.Normalize()

	assert.Equal(t, 1, ts.DaysInHot, "DaysInHot should default to 1")
	assert.Equal(t, 0, ts.DaysInWarm, "DaysInWarm should default to 0 when negative")
	assert.Equal(t, int64(100), ts.MaxQueryRate, "MaxQueryRate should be raised to MinQueryRate")
}

func TestTimeSeries_Normalize_ValidValues(t *testing.T) {
	ts := TimeSeries{DaysInHot: 7, DaysInWarm: 30, MinQueryRate: 50, MaxQueryRate: 200}
	ts.Normalize()

	assert.Equal(t, 7, ts.DaysInHot)
	assert.Equal(t, 30, ts.DaysInWarm)
	assert.Equal(t, int64(200), ts.MaxQueryRate)
}

// --- Vector.Normalize tests ---

func TestVector_Normalize_AllDefaults(t *testing.T) {
	v := Vector{}
	v.Normalize()

	assert.Equal(t, int64(1000), v.IncrementHint, "IncrementHint should default to 1000")
	assert.Equal(t, 16, v.MaxEdges, "MaxEdges should default to 16")
	assert.Equal(t, "hnsw", v.VectorEngineType, "VectorEngineType should default to hnsw")
	assert.Equal(t, 8, v.SubVectors, "SubVectors should default to 8")
	assert.Equal(t, 4, v.NList, "NList should default to 4")
	assert.Equal(t, 8, v.CodeSize, "CodeSize should default to 8")
}

func TestVector_Normalize_IncrementHintBelowMinimum(t *testing.T) {
	v := Vector{IncrementHint: 500, VectorEngineType: "hnsw", MaxEdges: 16, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(1000), v.IncrementHint)
}

func TestVector_Normalize_IncrementHintAboveMinimum(t *testing.T) {
	v := Vector{IncrementHint: 5000, VectorEngineType: "hnsw", MaxEdges: 16, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(5000), v.IncrementHint, "should keep values above 1000")
}

func TestVector_Normalize_ReplicasProd(t *testing.T) {
	v := Vector{Config: "prod", VectorEngineType: "hnsw", MaxEdges: 16, IncrementHint: 1000, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(1), v.Replicas, "prod config should set replicas to 1")
}

func TestVector_Normalize_ReplicasDev(t *testing.T) {
	v := Vector{Config: "dev", VectorEngineType: "hnsw", MaxEdges: 16, IncrementHint: 1000, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(0), v.Replicas, "dev config should set replicas to 0")
}

func TestVector_Normalize_NegativeReplicas(t *testing.T) {
	v := Vector{Replicas: -5, VectorEngineType: "hnsw", MaxEdges: 16, IncrementHint: 1000, SubVectors: 8, NList: 4, CodeSize: 8}
	v.Normalize()
	assert.Equal(t, int64(1), v.Replicas, "negative replicas should normalize to 1")
}

// --- Vector.validateOnDiskMode tests ---

func TestVector_ValidateOnDiskMode_Disabled(t *testing.T) {
	v := Vector{OnDisk: false, CompressionLevel: 0, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.False(t, v.OnDisk)
	assert.Equal(t, 32, v.CompressionLevel, "should default to 32 when OnDisk is false")
}

func TestVector_ValidateOnDiskMode_DisabledWithExistingLevel(t *testing.T) {
	v := Vector{OnDisk: false, CompressionLevel: 8, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.Equal(t, 8, v.CompressionLevel, "should keep existing compression level")
}

func TestVector_ValidateOnDiskMode_EnabledHNSW(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 16, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk, "HNSW should support on-disk mode")
	assert.Equal(t, 16, v.CompressionLevel)
}

func TestVector_ValidateOnDiskMode_EnabledIVF(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 4, VectorEngineType: "ivf"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk, "IVF should support on-disk mode")
	assert.Equal(t, 4, v.CompressionLevel)
}

func TestVector_ValidateOnDiskMode_EnabledNMSLIB(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 8, VectorEngineType: "nmslib"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk, "NMSLIB should support on-disk mode")
	assert.Equal(t, 8, v.CompressionLevel)
}

func TestVector_ValidateOnDiskMode_UnsupportedEngine(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 8, VectorEngineType: "hnswfp16"}
	v.validateOnDiskMode()
	assert.False(t, v.OnDisk, "hnswfp16 should not support on-disk mode")
	assert.Equal(t, 32, v.CompressionLevel, "should reset to default")
}

func TestVector_ValidateOnDiskMode_InvalidCompressionLevel(t *testing.T) {
	v := Vector{OnDisk: true, CompressionLevel: 7, VectorEngineType: "hnsw"}
	v.validateOnDiskMode()
	assert.True(t, v.OnDisk)
	assert.Equal(t, 32, v.CompressionLevel, "invalid level should reset to 32")
}

func TestVector_ValidateOnDiskMode_DefaultCompressionLevel(t *testing.T) {
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

func TestEstimateRequest_Normalize_DefaultRedundancy(t *testing.T) {
	er := EstimateRequest{Region: "us-east-1"}
	er.Normalize()

	assert.NotNil(t, er.Redundancy, "Redundancy should be set to non-nil")
	assert.True(t, *er.Redundancy, "Default redundancy should be true")
}

func TestEstimateRequest_Normalize_ExplicitRedundancy(t *testing.T) {
	redundancyOff := false
	er := EstimateRequest{Region: "us-east-1", Redundancy: &redundancyOff}
	er.Normalize()

	assert.False(t, *er.Redundancy, "Explicit false should be preserved")
}

func TestEstimateRequest_Normalize_WithSearch(t *testing.T) {
	er := EstimateRequest{
		Region: "us-east-1",
		Search: &Search{MinQueryRate: 100, MaxQueryRate: 50},
	}
	er.Normalize()

	assert.Equal(t, int64(100), er.Search.MaxQueryRate, "Search should be normalized")
}

func TestEstimateRequest_Normalize_WithTimeSeries(t *testing.T) {
	er := EstimateRequest{
		Region:     "us-east-1",
		TimeSeries: &TimeSeries{DaysInHot: 0, MinQueryRate: 10, MaxQueryRate: 5},
	}
	er.Normalize()

	assert.Equal(t, 1, er.TimeSeries.DaysInHot, "TimeSeries should be normalized")
	assert.Equal(t, int64(10), er.TimeSeries.MaxQueryRate)
}

func TestEstimateRequest_Normalize_WithVector(t *testing.T) {
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

func TestVector_GetRequiredMemoryInBytes_HNSW(t *testing.T) {
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

func TestVector_GetRequiredMemoryInBytes_HNSWWithOnDisk(t *testing.T) {
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

func TestVector_GetRequiredMemoryInBytes_UnsupportedEngine(t *testing.T) {
	v := Vector{VectorEngineType: "unknown"}
	_, _, err := v.GetRequiredMemoryInBytes()
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "engine type is not supported")
}

func TestVector_GetRequiredMemoryInBytes_AllEngines(t *testing.T) {
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

func TestVector_GetRequiredMemoryInGB(t *testing.T) {
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

func TestVector_CalculateRequiredMemory(t *testing.T) {
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
