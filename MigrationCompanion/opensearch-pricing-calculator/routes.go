// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"html"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/riandyrn/otelchi"
	httpSwagger "github.com/swaggo/http-swagger"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
	"go.uber.org/zap"
)

// Define metrics as package variables to be accessible throughout the application
var (
	provisionedEstimateCounter   metric.Int64Counter
	provisionedEstimateLatency   metric.Float64Histogram
	provisionedEstimateSuccesses metric.Int64Counter
	provisionedEstimateFailures  metric.Int64Counter

	serverlessEstimateCounter   metric.Int64Counter
	serverlessEstimateLatency   metric.Float64Histogram
	serverlessEstimateSuccesses metric.Int64Counter
	serverlessEstimateFailures  metric.Int64Counter
)

// initMetrics initializes the metrics for the application
func initMetrics(meter metric.Meter) error {
	var err error

	// Create counter for tracking the number of provisioned estimate requests
	provisionedEstimateCounter, err = meter.Int64Counter(
		"provisioned_estimate_requests_total",
		metric.WithDescription("Total number of provisioned estimate requests"),
	)
	if err != nil {
		return err
	}

	// Create histogram for tracking the latency of provisioned estimate requests
	provisionedEstimateLatency, err = meter.Float64Histogram(
		"provisioned_estimate_latency_seconds",
		metric.WithDescription("Latency of provisioned estimate requests in seconds"),
	)
	if err != nil {
		return err
	}

	// Create counter for tracking successful provisioned estimate requests
	provisionedEstimateSuccesses, err = meter.Int64Counter(
		"provisioned_estimate_successes_total",
		metric.WithDescription("Total number of successful provisioned estimate requests"),
	)
	if err != nil {
		return err
	}

	// Create counter for tracking failed provisioned estimate requests
	provisionedEstimateFailures, err = meter.Int64Counter(
		"provisioned_estimate_failures_total",
		metric.WithDescription("Total number of failed provisioned estimate requests"),
	)
	if err != nil {
		return err
	}

	// Serverless estimate metrics
	serverlessEstimateCounter, err = meter.Int64Counter(
		"serverless_estimate_requests_total",
		metric.WithDescription("Total number of serverless estimate requests"),
	)
	if err != nil {
		return err
	}

	serverlessEstimateLatency, err = meter.Float64Histogram(
		"serverless_estimate_latency_seconds",
		metric.WithDescription("Latency of serverless estimate requests in seconds"),
	)
	if err != nil {
		return err
	}

	serverlessEstimateSuccesses, err = meter.Int64Counter(
		"serverless_estimate_successes_total",
		metric.WithDescription("Total number of successful serverless estimate requests"),
	)
	if err != nil {
		return err
	}

	serverlessEstimateFailures, err = meter.Int64Counter(
		"serverless_estimate_failures_total",
		metric.WithDescription("Total number of failed serverless estimate requests"),
	)
	if err != nil {
		return err
	}

	return nil
}

// routes returns the HTTP handler for the application.
//
// The handler is a chi.Mux with the following middleware:
//
//  1. middleware.RequestID: adds a Request-Id header to the response.
//  2. middleware.RealIP: sets the RemoteAddr field of the request to the real IP
//     address of the client.
//  3. middleware.Logger: logs the request and response.
//  4. middleware.Recoverer: recovers from panics and logs the error.
//  5. EnableCors: enables CORS for the application.
//  6. otelchi.Middleware: adds OpenTelemetry instrumentation to the handler.
//
// The handler also mounts the following routes:
//
//   - /swagger: a Swagger UI for the application.
//   - /: the home page of the application.
//   - /serverless/v2/estimate: estimates the cost of an OpenSearch cluster.
//   - /serverless/regions: returns the list of regions for OpenSearch.
//   - /serverless/price: returns the price of an OpenSearch cluster.
//   - /serverless/cache/invalidate: invalidates the cache for the serverless
//     estimates.
//   - /provisioned/regions: returns the list of regions for OpenSearch.
//   - /provisioned/price: returns the price of an OpenSearch cluster.
//   - /provisioned/cache/invalidate: invalidates the cache for the provisioned
//     estimates.
//   - /provisioned/estimate: estimates the cost of an OpenSearch cluster.
//   - /provisioned/pricingOptions: returns the pricing options for an OpenSearch
//     cluster.
//   - /provisioned/instanceFamilyOptions: returns the instance family options for
//     an OpenSearch cluster.
//   - /provisioned/instanceFamilyOptions/{region}: returns the instance family options for
//     an OpenSearch cluster.
//   - /provisioned/instanceTypesByFamily: returns the instance types by family options for
//     an OpenSearch cluster.
//   - /provisioned/instanceTypesByFamily/{region}: returns the instance types by family options for
//     an OpenSearch cluster.
//   - /provisioned/warmInstanceTypes: returns available warm instance types for vector workloads.
//   - /metrics: returns the Prometheus metrics for the application.
func (app *application) routes() http.Handler {

	// Initialize metrics using the global MeterProvider (set by telemetry.Init)
	meter := otel.GetMeterProvider().Meter("opensearch-calc")
	if err := initMetrics(meter); err != nil {
		app.logger.Fatal("error initializing metrics", zap.Error(err))
	}

	//create a app mux
	mux := chi.NewRouter()

	mux.Use(middleware.RequestID)
	mux.Use(middleware.RealIP)

	// Replace standard logger with structured logging middleware
	mux.Use(app.structuredLogger)

	mux.Use(middleware.Recoverer)
	mux.Use(app.EnableCors)

	// Limit request body size to prevent memory exhaustion
	mux.Use(app.LimitRequestBody)

	// Apply general rate limiting to all requests
	mux.Use(app.RateLimit)

	mux.Use(otelchi.Middleware("opensearch-calc", otelchi.WithChiRoutes(mux)))

	app.logger.Info("mounting swagger documentation", zap.String("path", "/swagger"))
	mux.Mount("/swagger", httpSwagger.WrapHandler)

	app.logger.Info("registering route handlers")

	mux.Get("/", app.logRoute("home", app.Home))
	mux.Get("/health", app.logRoute("health", app.Health))

	mux.Post("/serverless/v2/estimate", app.logRoute("serverless_estimate_v2", app.instrumentServerlessEstimate(app.ServerlessEstimateV2)))

	mux.Get("/serverless/regions", app.logRoute("serverless_regions", app.ServerlessRegions))

	mux.Get("/serverless/price", app.logRoute("serverless_price", app.ServerlessPrice))

	// Cache invalidation endpoints have stricter rate limits
	mux.Post("/serverless/cache/invalidate", app.logRoute("invalidate_serverless_cache", app.RateLimitCacheInvalidation(app.InvalidateServerlessCache)))

	mux.Get("/provisioned/regions", app.logRoute("provisioned_regions", app.ProvisionedRegions))

	mux.Get("/provisioned/price", app.logRoute("provisioned_price", app.ProvisionedPrice))

	mux.Post("/provisioned/cache/invalidate", app.logRoute("invalidate_provisioned_cache", app.RateLimitCacheInvalidation(app.InvalidateProvisionedCache)))

	// Add instrumentation to the provisioned estimate route
	mux.Post("/provisioned/estimate", app.logRoute("provisioned_estimate", app.instrumentProvisionedEstimate(app.ProvisionedEstimate)))

	mux.Get("/provisioned/pricingOptions", app.logRoute("provisioned_pricing_options", app.ProvisionedPricingOptions))

	mux.Get("/provisioned/instanceFamilyOptions", app.logRoute("provisioned_instance_family_options", app.ProvisionedInstanceFamilyOptions))

	mux.Get("/provisioned/instanceFamilyOptions/{region}", app.logRoute("provisioned_instance_family_options_by_region", app.ProvisionedInstanceFamilyOptions))

	mux.Get("/provisioned/instanceTypesByFamily", app.logRoute("provisioned_instance_types_by_family", app.ProvisionedInstanceTypesByFamily))
	mux.Get("/provisioned/instanceTypesByFamily/{region}", app.logRoute("provisioned_instance_types_by_family_by_region", app.ProvisionedInstanceTypesByFamily))

	mux.Get("/provisioned/warmInstanceTypes", app.logRoute("provisioned_warm_instance_types", app.ProvisionedWarmInstanceTypes))
	mux.Get("/provisioned/warmInstanceTypes/{region}", app.logRoute("provisioned_warm_instance_types_by_region", app.ProvisionedWarmInstanceTypes))

	// Assistant routes (if available)
	if app.assistantHandler != nil {
		mux.Post("/api/assistant/estimate", app.logRoute("assistant_estimate", app.assistantHandler.HandleEstimate))
		mux.Post("/api/assistant/clearCache", app.logRoute("assistant_clear_cache", app.assistantHandler.HandleClearCache))
		mux.Get("/api/assistant/cache/stats", app.logRoute("assistant_cache_stats", app.assistantHandler.HandleCacheStats))
		app.logger.Info("registered assistant routes")
	}

	app.logger.Info("registering metrics handler", zap.String("path", "/metrics"))
	mux.Handle("/metrics", promhttp.Handler())

	app.logger.Info("router configuration complete", zap.Int("total_routes", len(mux.Routes())))
	return mux
}

// instrumentProvisionedEstimate wraps the ProvisionedEstimate handler with instrumentation
func (app *application) instrumentProvisionedEstimate(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Create a custom response writer to capture the status code
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)

		// Get request attributes for metrics
		requestID := middleware.GetReqID(r.Context())

		// Attempt to determine request type from the request body
		var requestType string
		var requestBody map[string]interface{}

		bodyBytes, err := io.ReadAll(r.Body)
		if err == nil {
			// Restore the body for the next handler
			r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))

			if err := json.Unmarshal(bodyBytes, &requestBody); err == nil {
				// Determine request type
				if _, ok := requestBody["search"]; ok {
					requestType = "search"
				} else if _, ok := requestBody["vector"]; ok {
					requestType = "vector"
				} else {
					requestType = "timeseries"
				}
			} else {
				requestType = "unknown"
				app.logger.Warn("could not determine request type",
					zap.Error(err),
					zap.String("request_id", requestID))
			}
		} else {
			requestType = "unknown"
			app.logger.Warn("could not read request body for instrumentation",
				zap.Error(err),
				zap.String("request_id", requestID))
			// Create a new empty body since we failed to read it
			r.Body = io.NopCloser(bytes.NewBuffer([]byte{}))
		}

		attrs := []attribute.KeyValue{
			attribute.String("request_id", requestID),
			attribute.String("method", r.Method),
			attribute.String("path", r.URL.Path),
			attribute.String("request_type", requestType),
		}

		// Start a trace span for the estimate computation
		tracer := otel.Tracer("opensearch-calc")
		ctx, span := tracer.Start(r.Context(), "provisioned.estimate",
			trace.WithAttributes(attrs...),
		)
		defer span.End()
		r = r.WithContext(ctx)

		// Increment request counter
		provisionedEstimateCounter.Add(r.Context(), 1, metric.WithAttributes(attrs...))

		// Track request start time
		startTime := time.Now()

		// Log the start of processing
		app.logger.Info("processing provisioned estimate request",
			zap.String("request_id", requestID),
			zap.String("method", r.Method),
			zap.String("path", r.URL.Path),
			zap.String("request_type", requestType))

		// Process the request
		next(ww, r)

		// Calculate request duration
		duration := time.Since(startTime)

		// Record latency
		provisionedEstimateLatency.Record(r.Context(), duration.Seconds(), metric.WithAttributes(attrs...))

		// Add status code to attributes
		statusAttrs := append(attrs, attribute.Int("status_code", ww.Status()))

		// Record success or failure based on status code
		if ww.Status() >= 400 {
			provisionedEstimateFailures.Add(r.Context(), 1, metric.WithAttributes(statusAttrs...))
			app.logger.Warn("provisioned estimate request failed",
				zap.String("request_id", requestID),
				zap.String("request_type", requestType),
				zap.Int("status", ww.Status()),
				zap.Duration("duration", duration))
		} else {
			provisionedEstimateSuccesses.Add(r.Context(), 1, metric.WithAttributes(statusAttrs...))
			app.logger.Info("provisioned estimate request succeeded",
				zap.String("request_id", requestID),
				zap.String("request_type", requestType),
				zap.Int("status", ww.Status()),
				zap.Int("bytes_written", ww.BytesWritten()),
				zap.Duration("duration", duration))
		}
	}
}

// instrumentServerlessEstimate wraps the ServerlessEstimateV2 handler with instrumentation
func (app *application) instrumentServerlessEstimate(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
		requestID := middleware.GetReqID(r.Context())

		// Determine request type from the request body
		var requestType string
		var requestBody map[string]interface{}

		bodyBytes, err := io.ReadAll(r.Body)
		if err == nil {
			r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))

			if err := json.Unmarshal(bodyBytes, &requestBody); err == nil {
				if _, ok := requestBody["search"]; ok {
					requestType = "search"
				} else if _, ok := requestBody["timeSeries"]; ok {
					requestType = "timeseries"
				} else if _, ok := requestBody["vector"]; ok {
					requestType = "vector"
				} else {
					requestType = "unknown"
				}
			} else {
				requestType = "unknown"
			}
		} else {
			requestType = "unknown"
			r.Body = io.NopCloser(bytes.NewBuffer([]byte{}))
		}

		attrs := []attribute.KeyValue{
			attribute.String("request_id", requestID),
			attribute.String("method", r.Method),
			attribute.String("path", r.URL.Path),
			attribute.String("request_type", requestType),
		}

		tracer := otel.Tracer("opensearch-calc")
		ctx, span := tracer.Start(r.Context(), "serverless.estimate",
			trace.WithAttributes(attrs...),
		)
		defer span.End()
		r = r.WithContext(ctx)

		serverlessEstimateCounter.Add(r.Context(), 1, metric.WithAttributes(attrs...))
		startTime := time.Now()

		next(ww, r)

		duration := time.Since(startTime)
		serverlessEstimateLatency.Record(r.Context(), duration.Seconds(), metric.WithAttributes(attrs...))

		statusAttrs := append(attrs, attribute.Int("status_code", ww.Status()))

		if ww.Status() >= 400 {
			serverlessEstimateFailures.Add(r.Context(), 1, metric.WithAttributes(statusAttrs...))
			app.logger.Warn("serverless estimate request failed",
				zap.String("request_id", requestID),
				zap.String("request_type", requestType),
				zap.Int("status", ww.Status()),
				zap.Duration("duration", duration))
		} else {
			serverlessEstimateSuccesses.Add(r.Context(), 1, metric.WithAttributes(statusAttrs...))
			app.logger.Info("serverless estimate request succeeded",
				zap.String("request_id", requestID),
				zap.String("request_type", requestType),
				zap.Int("status", ww.Status()),
				zap.Int("bytes_written", ww.BytesWritten()),
				zap.Duration("duration", duration))
		}
	}
}

// structuredLogger is a custom middleware that logs request details using zap
func (app *application) structuredLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Skip logging for health checks (ALB polls every 30s)
		if r.URL.Path == "/health" {
			next.ServeHTTP(w, r)
			return
		}

		start := time.Now()

		// Create a custom response writer to capture the status code
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)

		// Process the request
		next.ServeHTTP(ww, r)

		// Log after request is processed
		duration := time.Since(start)

		// Extract user identity from request headers (set by Lambda@Edge or frontend)
		userID := r.Header.Get("X-User-ID")
		userEmail := r.Header.Get("X-User-Email")

		// Determine log level based on status code
		if ww.Status() >= 500 {
			app.logger.Error("request completed",
				zap.String("method", r.Method),
				zap.String("path", r.URL.Path),
				zap.String("query", html.EscapeString(r.URL.RawQuery)), // import "html"
				zap.String("remote_addr", r.RemoteAddr),
				zap.String("user_agent", r.UserAgent()),
				zap.String("request_id", middleware.GetReqID(r.Context())),
				zap.String("user_id", userID),
				zap.String("user_email", userEmail),
				zap.Int("status", ww.Status()),
				zap.Int("bytes_written", ww.BytesWritten()),
				zap.Duration("duration", duration),
			)
		} else if ww.Status() >= 400 {
			app.logger.Warn("request completed",
				zap.String("method", r.Method),
				zap.String("path", r.URL.Path),
				zap.String("query", html.EscapeString(r.URL.RawQuery)), // import "html"
				zap.String("remote_addr", r.RemoteAddr),
				zap.String("user_agent", r.UserAgent()),
				zap.String("request_id", middleware.GetReqID(r.Context())),
				zap.String("user_id", userID),
				zap.String("user_email", userEmail),
				zap.Int("status", ww.Status()),
				zap.Int("bytes_written", ww.BytesWritten()),
				zap.Duration("duration", duration),
			)
		} else {
			app.logger.Info("request completed",
				zap.String("method", r.Method),
				zap.String("path", r.URL.Path),
				zap.String("query", html.EscapeString(r.URL.RawQuery)), // import "html"
				zap.String("remote_addr", r.RemoteAddr),
				zap.String("user_agent", r.UserAgent()),
				zap.String("request_id", middleware.GetReqID(r.Context())),
				zap.String("user_id", userID),
				zap.String("user_email", userEmail),
				zap.Int("status", ww.Status()),
				zap.Int("bytes_written", ww.BytesWritten()),
				zap.Duration("duration", duration),
			)
		}
	})
}

// logRoute wraps an HTTP handler with logging specific to the route
func (app *application) logRoute(name string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		app.logger.Debug("handling request",
			zap.String("route", name),
			zap.String("method", r.Method),
			zap.String("path", r.URL.Path),
			zap.String("request_id", middleware.GetReqID(r.Context())),
		)

		start := time.Now()
		h(w, r)
		duration := time.Since(start)

		app.logger.Debug("request handled",
			zap.String("route", name),
			zap.String("method", r.Method),
			zap.String("path", r.URL.Path),
			zap.String("request_id", middleware.GetReqID(r.Context())),
			zap.Duration("processing_time", duration),
		)
	}
}
