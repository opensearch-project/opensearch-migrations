// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package mcp

import (
	"encoding/json"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"go.uber.org/zap"
)

// createSearchRequestWithDefaults creates a request with search defaults and overlays user args.
func createSearchRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		Search: provisioned.GetDefaultSearchRequest(),
	}

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		zap.L().Warn("failed to unmarshal search request, using defaults", zap.Error(err))
	}
	return
}

// createVectorRequestWithDefaults creates a request with vector defaults and overlays user args.
func createVectorRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		Vector: provisioned.GetDefaultVectorRequest(),
	}

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		zap.L().Warn("failed to unmarshal vector request, using defaults", zap.Error(err))
	}
	return
}

// createTimeSeriesRequestWithDefaults creates a request with time series defaults and overlays user args.
func createTimeSeriesRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		TimeSeries: provisioned.GetDefaultTimeSeriesRequest(),
	}

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		zap.L().Warn("failed to unmarshal time series request, using defaults", zap.Error(err))
	}
	return
}
