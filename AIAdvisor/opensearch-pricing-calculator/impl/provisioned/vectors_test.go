// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"github.com/stretchr/testify/assert"
	"testing"
)

func TestVectorEstimateRequest_GetRequiredMemory_S3(t *testing.T) {
	r := &VectorEstimateRequest{
		VectorEngineType: "s3",
		VectorCount:      10000000,
		DimensionsCount:  768,
		MaxEdges:         16,
		Replicas:         1,
	}
	mem, calcString, err := r.GetRequiredMemory()
	assert.NoError(t, err)
	assert.Equal(t, 0.0, mem, "S3 engine should require 0 vector memory")
	assert.Contains(t, calcString, "s3")
	assert.Contains(t, calcString, "vectorMemory:0")
}

func TestVectorEstimateRequest_Normalize_S3(t *testing.T) {
	tests := []struct {
		name                  string
		input                 VectorEstimateRequest
		expectedWarm          int
		expectedCold          int
		expectedOnDisk        bool
		expectedInstanceTypes []string
	}{
		{
			name: "S3 resets warm and cold to 0",
			input: VectorEstimateRequest{
				VectorEngineType: "s3",
				WarmPercentage:   30,
				ColdPercentage:   20,
				Region:           "US East (N. Virginia)",
			},
			expectedWarm: 0,
			expectedCold: 0,
		},
		{
			name: "S3 resets onDisk to false",
			input: VectorEstimateRequest{
				VectorEngineType: "s3",
				OnDisk:           true,
				CompressionLevel: 16,
				Region:           "US East (N. Virginia)",
			},
			expectedOnDisk: false,
		},
		{
			name: "S3 restricts to OpenSearch Optimized instances",
			input: VectorEstimateRequest{
				VectorEngineType: "s3",
				Region:           "US East (N. Virginia)",
			},
			expectedInstanceTypes: []string{"or1.", "or2.", "om2.", "oi2."},
		},
		{
			name: "S3 filters non-OpenSearch-Optimized from explicit instance types",
			input: VectorEstimateRequest{
				VectorEngineType: "s3",
				InstanceTypes:    []string{"c7i", "c8g", "m7i", "oi2", "om2", "or2", "r7i", "i7i"},
				Region:           "US East (N. Virginia)",
			},
			expectedInstanceTypes: []string{"oi2", "om2", "or2"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := tt.input
			r.Normalize()
			if tt.name == "S3 resets warm and cold to 0" {
				assert.Equal(t, 0, r.WarmPercentage, "Warm should be 0 for S3")
				assert.Equal(t, 0, r.ColdPercentage, "Cold should be 0 for S3")
			}
			if tt.name == "S3 resets onDisk to false" {
				assert.False(t, r.OnDisk, "OnDisk should be false for S3")
			}
			if tt.name == "S3 restricts to OpenSearch Optimized instances" {
				assert.Equal(t, tt.expectedInstanceTypes, r.InstanceTypes, "S3 should restrict to OS-optimized instances")
			}
			if tt.name == "S3 filters non-OpenSearch-Optimized from explicit instance types" {
				assert.Equal(t, tt.expectedInstanceTypes, r.InstanceTypes, "S3 should filter to OS-optimized instances only")
			}
		})
	}
}

func TestVectorEstimateRequest_HandleConfigGroups(t *testing.T) {
	type fields struct {
		Azs              int
		Replicas         int
		DedicatedManager bool
		MinimumJVM       float64
		Config           string
	}
	tests := []struct {
		name             string
		fields           fields
		az               int
		replica          int
		minJVM           float64
		dedicatedManager bool
	}{
		{name: "devTest", fields: fields{Config: "dev"}, replica: 0, minJVM: 2.0, az: 1, dedicatedManager: false},
		{name: "production", fields: fields{Config: "production"}, replica: 1, minJVM: 8.0, az: 3, dedicatedManager: true},
		{name: "custom", fields: fields{Config: "custom", MinimumJVM: 8.0, Replicas: 1, Azs: 3, DedicatedManager: true}, replica: 1, minJVM: 8.0, az: 3, dedicatedManager: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := &VectorEstimateRequest{
				Azs:              tt.fields.Azs,
				Replicas:         tt.fields.Replicas,
				DedicatedManager: tt.fields.DedicatedManager,
				MinimumJVM:       tt.fields.MinimumJVM,
				Config:           tt.fields.Config,
			}
			r.Normalize()
			assert.Equal(t, tt.minJVM, r.MinimumJVM, "minimum JVM mismatch")
			assert.Equal(t, tt.replica, r.Replicas, "Replica mismatch")
			assert.Equal(t, tt.dedicatedManager, r.DedicatedManager, "Dedicated manager mismatch")
			assert.Equal(t, tt.az, r.Azs, "AZ mismatch")
		})
	}
}
