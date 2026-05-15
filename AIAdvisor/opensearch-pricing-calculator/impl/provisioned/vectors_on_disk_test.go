// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"testing"
)

func TestVectorEstimateRequest_OnDiskMode(t *testing.T) {
	tests := []struct {
		name              string
		vectorEngineType  string
		onDisk            bool
		compressionLevel  int
		expectedOnDisk    bool
		expectedCompLevel int
		shouldCompress    bool
	}{
		{
			name:              "HNSW with on-disk mode enabled",
			vectorEngineType:  "hnsw",
			onDisk:            true,
			compressionLevel:  8,
			expectedOnDisk:    true,
			expectedCompLevel: 8,
			shouldCompress:    true,
		},
		{
			name:              "IVF with on-disk mode enabled",
			vectorEngineType:  "ivf",
			onDisk:            true,
			compressionLevel:  16,
			expectedOnDisk:    true,
			expectedCompLevel: 16,
			shouldCompress:    true,
		},
		{
			name:              "HNSW with on-disk disabled",
			vectorEngineType:  "hnsw",
			onDisk:            false,
			compressionLevel:  8,
			expectedOnDisk:    false,
			expectedCompLevel: 8,
			shouldCompress:    false,
		},
		{
			name:              "HNSWFP16 with on-disk mode (should be disabled)",
			vectorEngineType:  "hnswfp16",
			onDisk:            true,
			compressionLevel:  8,
			expectedOnDisk:    false,
			expectedCompLevel: 32,
			shouldCompress:    false,
		},
		{
			name:              "IVFPQ with on-disk mode (should be disabled)",
			vectorEngineType:  "ivfpq",
			onDisk:            true,
			compressionLevel:  8,
			expectedOnDisk:    false,
			expectedCompLevel: 32,
			shouldCompress:    false,
		},
		{
			name:              "Invalid compression level should default to 32",
			vectorEngineType:  "hnsw",
			onDisk:            true,
			compressionLevel:  5, // Invalid - should default to 32
			expectedOnDisk:    true,
			expectedCompLevel: 32,
			shouldCompress:    true,
		},
		{
			name:              "Zero compression level should default to 32",
			vectorEngineType:  "hnsw",
			onDisk:            true,
			compressionLevel:  0,
			expectedOnDisk:    true,
			expectedCompLevel: 32,
			shouldCompress:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := &VectorEstimateRequest{
				VectorEngineType: tt.vectorEngineType,
				OnDisk:           tt.onDisk,
				CompressionLevel: tt.compressionLevel,
				VectorCount:      1000,
				DimensionsCount:  384,
				MaxEdges:         16,
				Replicas:         1,
			}

			// Call Normalize to apply validation
			req.Normalize()

			// Check that validation worked correctly
			if req.OnDisk != tt.expectedOnDisk {
				t.Errorf("Expected OnDisk to be %v, got %v", tt.expectedOnDisk, req.OnDisk)
			}

			if req.CompressionLevel != tt.expectedCompLevel {
				t.Errorf("Expected CompressionLevel to be %d, got %d", tt.expectedCompLevel, req.CompressionLevel)
			}

			// Test memory calculation
			memory, calcString, err := req.GetRequiredMemory()
			if err != nil {
				t.Fatalf("GetRequiredMemory failed: %v", err)
			}

			// Verify compression was applied in the calculation string
			if tt.shouldCompress && req.OnDisk {
				if !contains(calcString, "onDisk:true") {
					t.Errorf("Expected calculation string to contain 'onDisk:true', got: %s", calcString)
				}
				if !contains(calcString, "compression:") {
					t.Errorf("Expected calculation string to contain 'compression:', got: %s", calcString)
				}
			} else {
				if contains(calcString, "onDisk:true") {
					t.Errorf("Did not expect calculation string to contain 'onDisk:true', got: %s", calcString)
				}
			}

			// Verify memory is positive
			if memory < 0 {
				t.Errorf("Expected positive memory value, got %f", memory)
			}
		})
	}
}

func TestIsUncompressedEngine(t *testing.T) {
	tests := []struct {
		name       string
		engineType string
		expected   bool
	}{
		{"HNSW", "hnsw", true},
		{"HNSW uppercase", "HNSW", true},
		{"IVF", "ivf", true},
		{"IVF uppercase", "IVF", true},
		{"NSMLIB", "nsmlib", true},
		{"HNSWFP16", "hnswfp16", false},
		{"HNSWINT8", "hnswint8", false},
		{"HNSWBV", "hnswbv", false},
		{"IVFPQ", "ivfpq", false},
		{"IVFFP16", "ivffp16", false},
		{"IVFINT8", "ivfint8", false},
		{"IVFBV", "ivfbv", false},
		{"HNSWPQ", "hnswpq", false},
		{"Invalid", "invalid", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := isUncompressedEngine(tt.engineType)
			if result != tt.expected {
				t.Errorf("isUncompressedEngine(%s) = %v, expected %v", tt.engineType, result, tt.expected)
			}
		})
	}
}

func TestIsValidCompressionLevel(t *testing.T) {
	tests := []struct {
		name     string
		level    int
		expected bool
	}{
		{"Level 2", 2, true},
		{"Level 4", 4, true},
		{"Level 8", 8, true},
		{"Level 16", 16, true},
		{"Level 32", 32, true},
		{"Level 1", 1, false},
		{"Level 3", 3, false},
		{"Level 5", 5, false},
		{"Level 64", 64, false},
		{"Level 0", 0, false},
		{"Negative", -1, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := isValidCompressionLevel(tt.level)
			if result != tt.expected {
				t.Errorf("isValidCompressionLevel(%d) = %v, expected %v", tt.level, result, tt.expected)
			}
		})
	}
}

func TestVectorEstimateRequest_CompressionEffect(t *testing.T) {
	// Test that compression actually reduces memory usage
	baseReq := &VectorEstimateRequest{
		VectorEngineType: "hnsw",
		VectorCount:      10000,
		DimensionsCount:  384,
		MaxEdges:         16,
		Replicas:         1,
		OnDisk:           false,
		CompressionLevel: 32,
	}

	compressedReq := &VectorEstimateRequest{
		VectorEngineType: "hnsw",
		VectorCount:      10000,
		DimensionsCount:  384,
		MaxEdges:         16,
		Replicas:         1,
		OnDisk:           true,
		CompressionLevel: 8,
	}

	baseReq.Normalize()
	compressedReq.Normalize()

	baseMemory, _, err := baseReq.GetRequiredMemory()
	if err != nil {
		t.Fatalf("Base GetRequiredMemory failed: %v", err)
	}

	compressedMemory, _, err := compressedReq.GetRequiredMemory()
	if err != nil {
		t.Fatalf("Compressed GetRequiredMemory failed: %v", err)
	}

	// Compressed memory should be less than base memory
	if compressedMemory >= baseMemory {
		t.Errorf("Expected compressed memory (%f) to be less than base memory (%f)", compressedMemory, baseMemory)
	}

	// The compression ratio is not exactly the compression level because only the
	// dimension portion of memory is compressed, not the edge portion.
	// Formula: 1.1 * (dimFactor * dimensions + 8 * maxEdges) * vectorCount
	// Base dimFactor = 4.0, Compressed dimFactor = 4.0 / 8 = 0.5
	// Base memory = 1.1 * (4.0 * 384 + 8 * 16) * 10000 = 1.1 * 1664 * 10000
	// Compressed memory = 1.1 * (0.5 * 384 + 8 * 16) * 10000 = 1.1 * 320 * 10000
	// Expected ratio = 1664 / 320 = 5.2
	expectedRatio := 5.2
	actualRatio := baseMemory / compressedMemory
	tolerance := 0.2 // Allow some tolerance for floating point operations

	if actualRatio < expectedRatio-tolerance || actualRatio > expectedRatio+tolerance {
		t.Errorf("Expected compression ratio around %f, got %f", expectedRatio, actualRatio)
	}
}

// Helper function to check if a string contains a substring
func contains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > len(substr) && (s[:len(substr)] == substr || s[len(s)-len(substr):] == substr || containsInMiddle(s, substr)))
}

func containsInMiddle(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
