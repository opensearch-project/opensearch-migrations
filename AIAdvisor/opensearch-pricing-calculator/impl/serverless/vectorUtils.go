// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"errors"
	"fmt"
	"math"
	"strings"
)

const M = 16

// BytesToGb takes a number of bytes and returns the number of Gb as a float64.
// This is a simple convenience function for converting bytes to Gb.
func BytesToGb(bytes int64) float64 {
	return float64(bytes) / 1024 / 1024 / 1024
}

// VectorMemoryForDimensionsBytes takes a number of dimensions and returns the amount of memory required, in bytes, to store a vector of that dimensionality using the HNSW vector engine.
// This calculation is based on the formula given in the documentation for OpenSearch's KNN plugin, which is itself based on the paper "Hnswlib: A fast and efficient library for hierarchical navigable small world graphs".
// The result is rounded up to the next whole byte.
func VectorMemoryForDimensionsBytes(dimensions int64) int64 {
	// https://opensearch.org/docs/latest/search-plugins/knn/knn-index/#hnsw-memory-estimation
	bytes := 1.1 * (4*float64(dimensions) + 8*float64(M))
	// round up to next byte
	return int64(math.Ceil(bytes))
}

// GetRequiredMemoryInBytes takes a `Vector` and returns the amount of memory required, in bytes, to store the vector using the given vector engine.
// The result is rounded up to the next whole byte.
// The calculation is based on the formula given in the documentation for OpenSearch's KNN plugin, which is itself based on the paper "Hnswlib: A fast and efficient library for hierarchical navigable small world graphs".
// If the vector engine type is not supported, an error is returned.
// On-disk compression is applied only to HNSW and IVF engines by dividing the dimension factor by the compression level.
func (v *Vector) GetRequiredMemoryInBytes() (reqMemory int64, calcString string, err error) {
	// Product quantization memory formula — see OpenSearch k-NN plugin documentation
	var reqMemoryInBytes float64

	calcString = "vectorEngine:" + v.VectorEngineType

	switch strings.ToLower(v.VectorEngineType) {
	case "nsmlib", "hnsw":
		dimFactor := 4.0
		if v.OnDisk {
			dimFactor = dimFactor / float64(v.CompressionLevel)
			calcString += fmt.Sprintf(",onDisk:true,compression:%d", v.CompressionLevel)
		}
		reqMemoryInBytes = 1.1 * (dimFactor*float64(v.DimensionsCount) + 8.0*float64(v.MaxEdges)) * float64(v.VectorCount)
		calcString += fmt.Sprintf(",formula:1.1*(%.4f*dimensionsCount:%d+8.0*maxEdges:%d)*vectorCount:%d",
			dimFactor, v.DimensionsCount, v.MaxEdges, v.VectorCount)
	case "hnswfp16":
		reqMemoryInBytes = 1.1 * (2.0*float64(v.DimensionsCount) + 8.0*float64(v.MaxEdges)) * float64(v.VectorCount)
		calcString += fmt.Sprintf(",formula:1.1*(2.0*dimensionsCount:%d+8.0*maxEdges:%d)*vectorCount:%d",
			v.DimensionsCount, v.MaxEdges, v.VectorCount)
	case "hnswbv":
		reqMemoryInBytes = 1.1 * (float64(v.DimensionsCount)/8.0 + 8.0*float64(v.MaxEdges)) * float64(v.VectorCount)
		calcString += fmt.Sprintf(",formula:1.1*(dimensionsCount:%d/8.0+8.0*maxEdges:%d)*vectorCount:%d",
			v.DimensionsCount, v.MaxEdges, v.VectorCount)
	case "ivfbv":
		reqMemoryInBytes = 1.1 * ((float64(v.DimensionsCount) / 8 * float64(v.VectorCount)) + (float64(v.NList) * float64(v.DimensionsCount)))
		calcString += fmt.Sprintf(",formula:1.1*((dimensionsCount:%d/8*vectorCount:%d)+(nList:%d*dimensionsCount:%d))",
			v.DimensionsCount, v.VectorCount, v.NList, v.DimensionsCount)
	case "ivf":
		dimFactor := 4.0
		if v.OnDisk {
			dimFactor = dimFactor / float64(v.CompressionLevel)
			calcString += fmt.Sprintf(",onDisk:true,compression:%d", v.CompressionLevel)
		}
		reqMemoryInBytes = 1.1 * (((dimFactor*float64(v.DimensionsCount) + 24.0) * float64(v.VectorCount)) + (dimFactor * float64(v.NList) * float64(v.DimensionsCount)))
		calcString += fmt.Sprintf(",formula:1.1*((%.4f*dimensionsCount:%d+24.0)*vectorCount:%d+(%.4f*nList:%d*dimensionsCount:%d))",
			dimFactor, v.DimensionsCount, v.VectorCount, dimFactor, v.NList, v.DimensionsCount)
	case "ivfpq":
		comp1 := ((float64(v.CodeSize)/8.0)*float64(v.SubVectors) + 24.0) * float64(v.VectorCount)
		comp2 := 4.0 * float64(v.NList) * float64(v.DimensionsCount)
		comp3 := math.Pow(2, float64(v.CodeSize)) * 4.0 * float64(v.DimensionsCount)
		reqMemoryInBytes = 1.1 * (comp1 + comp2 + comp3)
		calcString += fmt.Sprintf(",formula:1.1*(((codeSize:%d/8.0)*subVectors:%d+24.0)*vectorCount:%d+(4.0*nList:%d*dimensionsCount:%d)+(2^codeSize:%d*4.0*dimensionsCount:%d))",
			v.CodeSize, v.SubVectors, v.VectorCount, v.NList, v.DimensionsCount, v.CodeSize, v.DimensionsCount)
	case "hnswpq":
		//bytes = pq\_code\_size/8*pq\_m + 24 + 8 * hnsw\_m)* num\_vectors  + num\_segments*(2^{pq\_code\_size} * 4 * d + 4*pq\_m*2^{pq\_code\_size}*2^{pq\_code\_size})
		comp1 := (float64(v.CodeSize)/8.0*float64(v.MaxEdges) + 24.0 + 8*float64(v.MaxEdges)) * float64(v.VectorCount)
		comp2 := (math.Pow(2, float64(v.CodeSize)) * 4.0 * float64(v.DimensionsCount)) + (4.0 * float64(v.MaxEdges) * math.Pow(2, float64(v.CodeSize)) * math.Pow(2, float64(v.CodeSize)))
		reqMemoryInBytes = 1.1 * (comp1 + comp2)
		calcString += fmt.Sprintf(",formula:1.1*(((codeSize:%d/8.0)*maxEdges:%d+24.0+8*maxEdges:%d)*vectorCount:%d+(2^codeSize:%d*4.0*dimensionsCount:%d)+(4.0*maxEdges:%d*2^codeSize:%d*2^codeSize:%d))",
			v.CodeSize, v.MaxEdges, v.MaxEdges, v.VectorCount, v.CodeSize, v.DimensionsCount, v.MaxEdges, v.CodeSize, v.CodeSize)
	default:
		calcString += ",error:unsupported_engine_type"
		return 0.0, calcString, errors.New("engine type is not supported")
	}

	reqMemory = int64(1+v.Replicas) * int64(math.Ceil(reqMemoryInBytes))
	calcString += fmt.Sprintf(",replicas:%d,totalBytes:%d", v.Replicas, reqMemory)

	return
}

func (v *Vector) GetRequiredMemoryInGB() (reqMemory float64, calcString string, err error) {
	reqMemoryInBytes, calcString, err := v.GetRequiredMemoryInBytes()
	if err != nil {
		return
	}
	return float64(reqMemoryInBytes) / BytesPerGB, calcString, err
}

func (v *Vector) CalculateRequiredMemory() (reqMemory float64, err error) {
	var calcString string
	v.VectorGraphSizeInMemory, calcString, err = v.GetRequiredMemoryInGB()
	if err != nil {
		return
	}
	v.VectorMemoryCalc = calcString
	return v.VectorGraphSizeInMemory, err
}

// isUncompressedEngine checks if the given vector engine type is uncompressed (HNSW or IVF)
// These are the only engines that support on-disk mode with compression
func isUncompressedEngine(engineType string) bool {
	engineLower := strings.ToLower(engineType)
	return engineLower == "hnsw" || engineLower == "nmslib" || engineLower == "ivf"
}

// isValidCompressionLevel checks if the compression level is one of the allowed values
func isValidCompressionLevel(level int) bool {
	validLevels := []int{2, 4, 8, 16, 32}
	for _, valid := range validLevels {
		if level == valid {
			return true
		}
	}
	return false
}

// validateOnDiskMode validates and normalizes the on-disk mode parameters
func (v *Vector) validateOnDiskMode() {
	// If OnDisk is false, ensure CompressionLevel is set to default (32) for consistency
	if !v.OnDisk {
		if v.CompressionLevel == 0 {
			v.CompressionLevel = 32
		}
		return
	}

	// OnDisk is true - validate engine type compatibility
	if !isUncompressedEngine(v.VectorEngineType) {
		// Reset OnDisk to false for incompatible engines
		v.OnDisk = false
		v.CompressionLevel = 32
		return
	}

	// Validate compression level
	if v.CompressionLevel == 0 {
		v.CompressionLevel = 32 // Set default
	} else if !isValidCompressionLevel(v.CompressionLevel) {
		// Reset to default for invalid compression levels
		v.CompressionLevel = 32
	}
}
