// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"context"
	"encoding/json"
	"go.uber.org/zap"
)

// Handler handles serverless estimate requests
type Handler struct {
	logger *zap.Logger
}

// NewHandler creates a new serverless estimate handler
func NewHandler(logger *zap.Logger) *Handler {
	return &Handler{
		logger: logger,
	}
}

// Handle handles a serverless estimate request
func (h *Handler) Handle(ctx context.Context, args map[string]interface{}) (interface{}, error) {
	h.logger.Info("handling serverless estimate request")

	// Convert the args to an EstimateRequest
	var request EstimateRequest
	if err := mapToStruct(args, &request); err != nil {
		return nil, err
	}

	// Normalize the request
	request.Normalize()

	// Calculate the estimate
	response, err := request.CalculateV2()
	if err != nil {
		return nil, err
	}

	return response, nil
}

// mapToStruct maps a map[string]interface{} to a struct
func mapToStruct(m map[string]interface{}, v interface{}) error {
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}
