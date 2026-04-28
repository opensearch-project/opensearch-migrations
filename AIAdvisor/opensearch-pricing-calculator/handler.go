// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/cache"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/provisioned"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/serverless"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.uber.org/zap"
)

// ErrorResponse represents a standardized error response from the API.
type ErrorResponse struct {
	Error   string            `json:"error"`
	Message string            `json:"message"`
	Code    string            `json:"code,omitempty"`
	Fields  map[string]string `json:"fields,omitempty"`
}

// errorResponse sends a JSON-formatted error response with the given status code and message.
func (app *application) errorResponse(w http.ResponseWriter, r *http.Request, status int, message string) {
	app.errorResponseWithCode(w, r, status, message, "")
}

// errorResponseWithCode sends a JSON-formatted error response with a machine-readable code.
func (app *application) errorResponseWithCode(w http.ResponseWriter, r *http.Request, status int, message, code string) {
	response := ErrorResponse{
		Error:   http.StatusText(status),
		Message: message,
		Code:    code,
	}

	out, err := json.Marshal(response)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(out)
}

// writeJSON marshals data to JSON and writes it to the response with the given status code.
// If marshaling fails, it logs the error and sends a 500 response.
func (app *application) writeJSON(w http.ResponseWriter, status int, data interface{}) {
	out, err := json.Marshal(data)
	if err != nil {
		app.logger.Error("failed to marshal JSON response", zap.Error(err))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(out)
}

// validationErrorResponse sends a 400 response with field-level validation errors.
func (app *application) validationErrorResponse(w http.ResponseWriter, r *http.Request, message string, fields map[string]string) {
	response := ErrorResponse{
		Error:   http.StatusText(http.StatusBadRequest),
		Message: message,
		Code:    "VALIDATION_ERROR",
		Fields:  fields,
	}

	out, err := json.Marshal(response)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusBadRequest)
	_, _ = w.Write(out)
}

// Home @Description Health check service
// @Tags Health
// @Produces json
// @Router / [get]
func (app *application) Home(w http.ResponseWriter, r *http.Request) {
	var payload = struct {
		Status  string `json:"status"`
		Message string `json:"message"`
		Version string `json:"version"`
	}{
		Status:  "active",
		Message: "Go OpenSearch Calculator up and running",
		Version: ServiceVersion,
	}
	app.writeJSON(w, http.StatusOK, payload)
}

// Health @Description Health check endpoint for load balancers
// @Tags Health
// @Produces json
// @Router /health [get]
func (app *application) Health(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"healthy"}`))
}

// ServerlessEstimateV2 Estimate for serverless
// @Tags Serverless
// @Success 200 {object} serverless.EstimationResponse
// @Accept json
// @Produce json
//
// @Param request body serverless.EstimateRequest true "Estimation request"
// @Router /serverless/v2/estimate [post]
func (app *application) ServerlessEstimateV2(w http.ResponseWriter, r *http.Request) {
	tracer := otel.Tracer("opensearch-calc")
	_, span := tracer.Start(r.Context(), "ServerlessEstimateV2.handle")
	defer span.End()

	var request serverless.EstimateRequest
	b, err := io.ReadAll(r.Body)
	if err != nil {
		app.logger.Error("failed to read request body", zap.Error(err))
		app.errorResponse(w, r, http.StatusBadRequest, "failed to read request body")
		return
	}
	if err = json.Unmarshal(b, &request); err != nil {
		app.logger.Error("failed to unmarshal request", zap.Error(err))
		app.errorResponse(w, r, http.StatusBadRequest, "invalid JSON request body")
		return
	}
	request.Normalize()
	response, err := request.CalculateV2()
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "calculation failed")
		app.logger.Error("serverless calculation failed", zap.Error(err))
		app.errorResponse(w, r, http.StatusInternalServerError, "calculation failed")
		return
	}
	app.writeJSON(w, http.StatusOK, response)
}

// ServerlessRegions Returns all serverless regions
// @Tags Serverless
// @Response 200 object serverless.RegionsResponse
// @Produces json
// @Router /serverless/regions [get]
func (app *application) ServerlessRegions(w http.ResponseWriter, r *http.Request) {
	regionNames := cache.GetServerlessPrice().GetAllRegions()
	regionInfos := regions.GetAllRegionInfos(regionNames)
	response := serverless.RegionsResponse{Regions: regionInfos}
	app.writeJSON(w, http.StatusOK, response)
}

// InvalidateServerlessCache Reloads Serverless price cache.
// @Tags Serverless
// @Response 200
// @Produces json
// @Router /serverless/cache/invalidate [post]
func (app *application) InvalidateServerlessCache(w http.ResponseWriter, r *http.Request) {
	cache.GetServerlessPrice().Invalidate()
	app.writeJSON(w, http.StatusOK, map[string]string{
		"message": "ServerlessPrice Cache Invalidated at " + time.Now().String(),
	})
}

// ServerlessPrice Returns the serverless pricing for all regions.
// @Tags Serverless
// @Response 200 object cache.ServerlessPrice
// @Produces json
// @Router /serverless/price [get]
func (app *application) ServerlessPrice(w http.ResponseWriter, r *http.Request) {
	app.writeJSON(w, http.StatusOK, cache.GetServerlessPrice())
}

// ProvisionedRegions Returns all provisioned service regions
// @Tags Provisioned
// @Response 200 object provisioned.RegionsResponse
// @Produces json
// @Router /provisioned/regions [get]
func (app *application) ProvisionedRegions(w http.ResponseWriter, r *http.Request) {
	// Use GetAllAvailableRegions to get all AWS regions with continent information
	regionInfos := regions.GetAllAvailableRegions()
	response := provisioned.RegionsResponse{Regions: regionInfos}
	app.writeJSON(w, http.StatusOK, response)
}

// InvalidateProvisionedCache Reloads provisioned price cache.
//
// Query Parameters:
//   - update=true: Triggers cache invalidation (downloads from AWS)
//   - region=<name>: Optional. Invalidates cache for specific region only (e.g., "US East (N. Virginia)")
//
// Examples:
//   - POST /provisioned/cache/invalidate?update=true - Invalidate all regions
//   - POST /provisioned/cache/invalidate?update=true&region=US%20East%20(N.%20Virginia) - Invalidate single region
//
// @Tags Provisioned
// @Response 200
// @Produces json
// @Router /provisioned/cache/invalidate [post]
func (app *application) InvalidateProvisionedCache(w http.ResponseWriter, r *http.Request) {
	err := r.ParseForm()
	if err != nil {
		app.errorResponse(w, r, http.StatusBadRequest, "failed to parse form")
		return
	}

	update := r.Form.Get("update")
	region := r.Form.Get("region")

	if update != "" {
		var status cache.InvalidationStatus

		if region != "" {
			// Region-specific invalidation
			app.logger.Info("starting region-specific cache invalidation", zap.String("region", region))
			status = cache.GetProvisionedPrice().InvalidateCacheForRegion(region)
		} else {
			// Full cache invalidation (all regions)
			app.logger.Info("starting full cache invalidation for all regions")
			status = cache.GetProvisionedPrice().InvalidateCache()
		}

		// Return status message
		app.writeJSON(w, http.StatusOK, map[string]interface{}{
			"message":     status.Message,
			"updatedTime": status.UpdatedTime,
			"region":      region,
		})
	} else {
		cache.GetProvisionedPrice().LoadFromLocalFile()
		app.writeJSON(w, http.StatusOK, map[string]string{"message": "Cache loaded from local file"})
	}
}

// ProvisionedPrice Returns the serverless pricing for all regions.
// @Tags Provisioned
// @Response 200 object cache.ProvisionedPrice
// @Produces json
// @Router /provisioned/price [get]
func (app *application) ProvisionedPrice(w http.ResponseWriter, r *http.Request) {
	app.writeJSON(w, http.StatusOK, cache.GetProvisionedPrice())
}

// ProvisionedEstimate Estimates Provisioned workload.
// @Tags Provisioned
// @Param request body provisioned.EstimateRequest true "Estimation request"
// @Response 200  {object} provisioned.EstimateResponse
// @Produces json
// @Router /provisioned/estimate [post]
func (app *application) ProvisionedEstimate(w http.ResponseWriter, r *http.Request) {
	tracer := otel.Tracer("opensearch-calc")
	ctx, span := tracer.Start(r.Context(), "ProvisionedEstimate.handle")
	defer span.End()
	r = r.WithContext(ctx)

	var request provisioned.EstimateRequest
	b, err := io.ReadAll(r.Body)
	if err == nil {
		err = json.Unmarshal(b, &request)
		if err != nil {
			app.logger.Error("failed to unmarshal request body",
				zap.Error(err),
				zap.String("request_id", middleware.GetReqID(r.Context())))
		}
	} else {
		app.logger.Error("failed to read request body",
			zap.Error(err),
			zap.String("request_id", middleware.GetReqID(r.Context())))
	}

	if err := request.Validate(); err != nil {
		app.logger.Warn("request validation failed",
			zap.Error(err),
			zap.String("request_id", middleware.GetReqID(r.Context())))
		w.WriteHeader(http.StatusBadRequest)
		out := "Invalid request"
		_, _ = w.Write([]byte(out))
		return
	}

	requestType := "timeseries" // default
	if request.Search != nil {
		requestType = "search"
		app.logger.Debug("processing search request",
			zap.String("request_id", middleware.GetReqID(r.Context())))
		request = createSearchRequestWithDefaults(b)
	} else if request.Vector != nil {
		requestType = "vector"
		app.logger.Debug("processing vector request",
			zap.String("request_id", middleware.GetReqID(r.Context())))
		request = createVectorRequestWithDefaults(b)
	} else {
		app.logger.Debug("processing timeseries request",
			zap.String("request_id", middleware.GetReqID(r.Context())))
		request = createTimeSeriesRequestWithDefaults(b)
	}
	span.SetAttributes(attribute.String("request_type", requestType))

	app.logger.Info("normalizing request",
		zap.String("request_type", requestType),
		zap.String("request_id", middleware.GetReqID(r.Context())))
	request.Normalize(app.logger)

	app.logger.Info("calculating estimate",
		zap.String("request_type", requestType),
		zap.String("request_id", middleware.GetReqID(r.Context())))
	response, err := request.Calculate()

	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "calculation failed")
		app.logger.Error("calculation failed",
			zap.Error(err),
			zap.String("request_type", requestType),
			zap.String("request_id", middleware.GetReqID(r.Context())))
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal server error"))
		return
	}

	// Set currency based on region
	response.Currency = request.GetCurrency()

	out, err := json.Marshal(response)
	if err != nil {
		app.logger.Error("failed to marshal response",
			zap.Error(err),
			zap.String("request_type", requestType),
			zap.String("request_id", middleware.GetReqID(r.Context())))
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal server error"))
		return
	}

	app.logger.Info("estimate calculation completed successfully",
		zap.String("request_type", requestType),
		zap.Int("cluster_configs", len(response.ClusterConfigs)),
		zap.String("request_id", middleware.GetReqID(r.Context())))

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(out)
}

func createTimeSeriesRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		TimeSeries: provisioned.GetDefaultTimeSeriesRequest(),
	}

	logger := zap.L()
	logger.Debug("creating time series request with defaults",
		zap.String("payload", string(requestBytes)))

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		logger.Error("failed to unmarshal time series request", zap.Error(err))
	}
	return
}

func createSearchRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		Search: provisioned.GetDefaultSearchRequest(),
	}

	logger := zap.L()
	logger.Debug("creating search request with defaults",
		zap.String("payload", string(requestBytes)))

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		logger.Error("failed to unmarshal search request", zap.Error(err))
	}
	return
}

func createVectorRequestWithDefaults(requestBytes []byte) (er provisioned.EstimateRequest) {
	er = provisioned.EstimateRequest{
		Vector: provisioned.GetDefaultVectorRequest(),
	}

	logger := zap.L()
	logger.Debug("creating vector request with defaults",
		zap.String("payload", string(requestBytes)))

	if err := json.Unmarshal(requestBytes, &er); err != nil {
		logger.Error("failed to unmarshal vector request", zap.Error(err))
	}
	return
}

// ProvisionedPricingOptions Returns all provisioned pricing options
// @Tags Provisioned
// @Response 200 object provisioned.PricingOptionsResponse
// @Produces json
// @Router /provisioned/pricingOptions [get]
func (app *application) ProvisionedPricingOptions(w http.ResponseWriter, r *http.Request) {
	response := provisioned.PricingOptionsResponse{}
	var nv []provisioned.NamedValues
	var values []string
	for k, v := range provisioned.PriceOptionsMap {
		nv = append(nv, provisioned.NamedValues{Name: k, Value: v})
		values = append(values, k)
	}
	response.NamedPricingOptions = &nv
	response.Options = &values
	app.writeJSON(w, http.StatusOK, response)
}

// ProvisionedInstanceFamilyOptions Returns all provisioned instance family options, optionally filtered by region
// @Tags Provisioned
// @Param region path string false "Region name"
// @Response 200 object provisioned.InstanceFamilyOptionsResponse
// @Response 404 object ErrorResponse
// @Produces json
// @Router /provisioned/instanceFamilyOptions [get]
// @Router /provisioned/instanceFamilyOptions/{region} [get]
func (app *application) ProvisionedInstanceFamilyOptions(w http.ResponseWriter, r *http.Request) {
	region := chi.URLParam(r, "region")
	if region == "" {
		response := provisioned.InstanceFamilyOptionsResponse{Options: provisioned.InstanceFamilies}
		app.writeJSON(w, http.StatusOK, response)
		return
	}

	// URL unescape the region value
	unescapedRegion, err := url.QueryUnescape(region)
	if err != nil {
		app.errorResponse(w, r, http.StatusBadRequest, "invalid region format")
		return
	}

	// Normalize region input to canonical display name for cache lookup
	normalizedRegion := regions.NormalizeRegionInput(unescapedRegion)

	// Get the provisioned price cache
	provisionedPrice, exists := cache.GetRegionProvisionedPrice(normalizedRegion)
	if exists != nil {
		app.errorResponse(w, r, http.StatusNotFound, "region not found")
		return
	}

	// Create a map to store unique instance families
	uniqueFamilies := make(map[string]bool)

	// Collect unique instance families from hot instances
	for _, instance := range provisionedPrice.HotInstances {
		if instance.Family != "" {
			uniqueFamilies[instance.Family] = true
		}
	}

	// Convert map to slice
	families := make([]string, 0, len(uniqueFamilies))
	for family := range uniqueFamilies {
		families = append(families, family)
	}

	response := provisioned.InstanceFamilyOptionsResponse{Options: families}
	app.writeJSON(w, http.StatusOK, response)
}

// ProvisionedInstanceTypesByFamily Returns available instance types grouped by family for a region
// @Tags Provisioned
// @Param region path string false "Region name"
// @Param families query string false "Comma-separated list of instance families"
// @Response 200 object provisioned.InstanceTypesByFamilyResponse
// @Response 404 object ErrorResponse
// @Produces json
// @Router /provisioned/instanceTypesByFamily [get]
// @Router /provisioned/instanceTypesByFamily/{region} [get]
func (app *application) ProvisionedInstanceTypesByFamily(w http.ResponseWriter, r *http.Request) {
	region := chi.URLParam(r, "region")
	if region != "" {
		// URL unescape the region value
		unescapedRegion, err := url.QueryUnescape(region)
		if err != nil {
			app.errorResponse(w, r, http.StatusBadRequest, "invalid region format")
			return
		}
		// Normalize region input to canonical display name for cache lookup
		region = regions.NormalizeRegionInput(unescapedRegion)
	}

	// Get families from query parameter
	familiesParam := r.URL.Query().Get("families")
	var families []string
	if familiesParam != "" {
		families = strings.Split(familiesParam, ",")
	}

	// Get the provisioned price cache
	var provisionedPrice price.ProvisionedRegion
	var exists error
	if region != "" {
		provisionedPrice, exists = cache.GetRegionProvisionedPrice(region)
		if exists != nil {
			app.errorResponse(w, r, http.StatusNotFound, "region not found")
			return
		}
	}

	// Create a map to store unique instance types by family
	uniqueTypes := make(map[string]map[string]bool)

	if region != "" {
		// Process instances from the specific region
		for instanceType, instanceUnit := range provisionedPrice.HotInstances {
			family := instanceUnit.Family
			if len(families) > 0 && !contains(families, family) {
				continue
			}

			// Get base instance type without size (e.g., "i4i" from "i4i.xlarge")
			baseType := strings.Split(instanceType, ".")[0]

			if _, exists := uniqueTypes[family]; !exists {
				uniqueTypes[family] = make(map[string]bool)
			}
			uniqueTypes[family][baseType] = true
		}
	} else {
		// Process instances from all regions
		allRegions := cache.GetProvisionedPrice()
		for _, regionData := range allRegions.Regions {
			for instanceType, instanceUnit := range regionData.HotInstances {
				family := instanceUnit.Family
				if len(families) > 0 && !contains(families, family) {
					continue
				}

				// Get base instance type without size
				baseType := strings.Split(instanceType, ".")[0]

				if _, exists := uniqueTypes[family]; !exists {
					uniqueTypes[family] = make(map[string]bool)
				}
				uniqueTypes[family][baseType] = true
			}
		}
	}

	// Convert map to response structure
	var response provisioned.InstanceTypesByFamilyResponse

	// Get sorted families for consistent output
	families = make([]string, 0, len(uniqueTypes))
	for family := range uniqueTypes {
		families = append(families, family)
	}
	sort.Strings(families)

	// Build response with sorted families and instance types
	for _, family := range families {
		types := uniqueTypes[family]
		instanceTypes := make([]string, 0, len(types))
		for baseType := range types {
			instanceTypes = append(instanceTypes, baseType)
		}
		sort.Strings(instanceTypes)

		response.InstanceTypes = append(response.InstanceTypes, provisioned.InstanceTypeFamily{
			Family:        family,
			InstanceTypes: instanceTypes,
		})
	}

	out, err := json.Marshal(response)
	if err != nil {
		app.errorResponse(w, r, http.StatusInternalServerError, "error marshaling response")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(out)
}

// ProvisionedWarmInstanceTypes Returns available warm instance types for vector workloads
// When a region is provided (via path parameter or query string), filters OI2 instances by regional availability
// @Tags Provisioned
// @Param region path string false "AWS region (e.g., 'US East (N. Virginia)' or 'us-east-1')"
// @Response 200 object provisioned.WarmInstanceTypesResponse
// @Produces json
// @Router /provisioned/warmInstanceTypes [get]
// @Router /provisioned/warmInstanceTypes/{region} [get]
func (app *application) ProvisionedWarmInstanceTypes(w http.ResponseWriter, r *http.Request) {
	// Get region from path parameter or query string
	region := chi.URLParam(r, "region")
	if region == "" {
		region = r.URL.Query().Get("region")
	}

	var response provisioned.WarmInstanceTypesResponse

	if region != "" {
		// Normalize region input (handles both "us-east-1" and "US East (N. Virginia)")
		normalizedRegion := regions.NormalizeRegionInput(region)

		// Get pricing data for the region
		provisionedRegion, err := cache.GetRegionProvisionedPrice(normalizedRegion)
		if err != nil {
			app.logger.Warn("region not found in price cache, returning all warm instance types",
				zap.String("region", region),
				zap.String("normalizedRegion", normalizedRegion),
				zap.Error(err))
			response = provisioned.GetWarmInstanceTypes()
		} else {
			// Filter warm instance types by regional availability
			response = provisioned.GetWarmInstanceTypesForRegion(provisionedRegion.HotInstances)
		}
	} else {
		// No region specified - return all warm instance types
		response = provisioned.GetWarmInstanceTypes()
	}

	out, err := json.Marshal(response)
	if err != nil {
		app.errorResponse(w, r, http.StatusInternalServerError, "error marshaling response")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(out)
}

// contains checks if a string is present in a slice
func contains(slice []string, str string) bool {
	for _, s := range slice {
		if s == str {
			return true
		}
	}
	return false
}
