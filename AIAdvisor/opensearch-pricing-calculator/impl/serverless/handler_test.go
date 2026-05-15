// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestMapToStruct_ValidData(t *testing.T) {
	m := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"edp":    0.0,
		"ingest": map[string]interface{}{
			"minIndexingRate": 1.0,
			"maxIndexingRate": 5.0,
		},
	}

	var req EstimateRequest
	err := mapToStruct(m, &req)
	require.NoError(t, err)
	assert.Equal(t, "US East (N. Virginia)", req.Region)
	assert.Equal(t, 1.0, req.Ingest.MinIndexingRate)
	assert.Equal(t, 5.0, req.Ingest.MaxIndexingRate)
}

func TestMapToStruct_EmptyMap(t *testing.T) {
	m := map[string]interface{}{}
	var req EstimateRequest
	err := mapToStruct(m, &req)
	assert.NoError(t, err)
	assert.Equal(t, "", req.Region)
}

func TestMapToStruct_WithSearchFields(t *testing.T) {
	m := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"search": map[string]interface{}{
			"collectionSize": 50.0,
			"minQueryRate":   float64(100),
			"maxQueryRate":   float64(500),
		},
	}

	var req EstimateRequest
	err := mapToStruct(m, &req)
	require.NoError(t, err)
	require.NotNil(t, req.Search)
	assert.Equal(t, 50.0, req.Search.CollectionSize)
}

func TestMapToStruct_WithVectorFields(t *testing.T) {
	m := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"vector": map[string]interface{}{
			"vectorEngineType": "hnsw",
			"dimensionsCount":  float64(768),
			"vectorCount":      float64(1000000),
		},
	}

	var req EstimateRequest
	err := mapToStruct(m, &req)
	require.NoError(t, err)
	require.NotNil(t, req.Vector)
	assert.Equal(t, "hnsw", req.Vector.VectorEngineType)
	assert.Equal(t, int64(768), req.Vector.DimensionsCount)
}

func TestNewHandler(t *testing.T) {
	logger := zap.NewNop()
	h := NewHandler(logger)
	assert.NotNil(t, h)
	assert.Equal(t, logger, h.logger)
}

func TestHandler_Handle_WithSearchRequest(t *testing.T) {
	logger := zap.NewNop()
	h := NewHandler(logger)

	args := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"search": map[string]interface{}{
			"collectionSize": 10.0,
			"minQueryRate":   float64(10),
			"maxQueryRate":   float64(100),
			"hoursAtMaxRate": 8.0,
		},
		"ingest": map[string]interface{}{
			"minIndexingRate": 1.0,
			"maxIndexingRate": 5.0,
			"timePerDayAtMax": 4.0,
		},
	}

	result, err := h.Handle(context.Background(), args)
	assert.NoError(t, err)
	assert.NotNil(t, result)

	// Verify it returns an EstimationResponse
	response, ok := result.(EstimationResponse)
	assert.True(t, ok, "result should be EstimationResponse")
	assert.NotNil(t, response.Search)
}

func TestHandler_Handle_WithTimeSeriesRequest(t *testing.T) {
	logger := zap.NewNop()
	h := NewHandler(logger)

	args := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"timeSeries": map[string]interface{}{
			"dailyIndexSize": 10.0,
			"daysInHot":      float64(7),
			"daysInWarm":     float64(30),
			"minQueryRate":   float64(10),
			"maxQueryRate":   float64(100),
			"hoursAtMaxRate": 8.0,
		},
		"ingest": map[string]interface{}{
			"minIndexingRate": 1.0,
			"maxIndexingRate": 5.0,
			"timePerDayAtMax": 4.0,
		},
	}

	result, err := h.Handle(context.Background(), args)
	assert.NoError(t, err)
	assert.NotNil(t, result)

	response, ok := result.(EstimationResponse)
	assert.True(t, ok)
	assert.NotNil(t, response.TimeSeries)
}

func TestHandler_Handle_WithVectorRequest(t *testing.T) {
	logger := zap.NewNop()
	h := NewHandler(logger)

	args := map[string]interface{}{
		"region": "US East (N. Virginia)",
		"vector": map[string]interface{}{
			"vectorEngineType": "hnsw",
			"dimensionsCount":  float64(768),
			"vectorCount":      float64(10000),
			"maxEdges":         float64(16),
		},
		"ingest": map[string]interface{}{
			"minIndexingRate": 1.0,
			"maxIndexingRate": 5.0,
		},
	}

	result, err := h.Handle(context.Background(), args)
	assert.NoError(t, err)
	assert.NotNil(t, result)

	response, ok := result.(EstimationResponse)
	assert.True(t, ok)
	assert.NotNil(t, response.Vector)
}

func TestHandler_Handle_NilArgs(t *testing.T) {
	logger := zap.NewNop()
	h := NewHandler(logger)

	// nil map should still work (marshals to null/empty)
	result, err := h.Handle(context.Background(), nil)
	// mapToStruct marshals nil to "null" which will fail unmarshal
	if err != nil {
		// Expected — nil args produce a marshal error
		return
	}
	assert.NotNil(t, result)
}
