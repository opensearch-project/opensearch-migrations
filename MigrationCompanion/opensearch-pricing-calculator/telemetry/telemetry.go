// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package telemetry

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	v4 "github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/exporters/stdout/stdoutmetric"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.uber.org/zap"
)

// Config holds configuration for OpenTelemetry initialization.
type Config struct {
	ServiceName    string
	ServiceVersion string
	Environment    string
	Logger         *zap.Logger
}

// Telemetry holds the initialized providers for graceful shutdown.
type Telemetry struct {
	traceProvider *sdktrace.TracerProvider
	meterProvider *sdkmetric.MeterProvider
	logger        *zap.Logger
}

// sigV4RoundTripper signs HTTP requests with AWS SigV4 for OpenSearch Ingestion.
type sigV4RoundTripper struct {
	base   http.RoundTripper
	signer *v4.Signer
	creds  aws.CredentialsProvider
	region string
}

func (s *sigV4RoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	creds, err := s.creds.Retrieve(req.Context())
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve AWS credentials for SigV4: %w", err)
	}

	err = s.signer.SignHTTP(req.Context(), creds, req, "UNSIGNED-PAYLOAD", "osis", s.region, time.Now())
	if err != nil {
		return nil, fmt.Errorf("failed to sign request with SigV4: %w", err)
	}

	return s.base.RoundTrip(req)
}

// Init initializes OpenTelemetry tracing and metrics.
// When OTEL_EXPORTER_OTLP_ENDPOINT is set, traces are exported via OTLP HTTP.
// If OTEL_EXPORTER_OTLP_INSECURE=true, a plain HTTP client is used (local dev).
// Otherwise, requests are signed with SigV4 for OpenSearch Ingestion (production).
// Metrics are always exported to stdout via periodic reader.
func Init(cfg Config) (*Telemetry, error) {
	ctx := context.Background()

	res, err := resource.New(ctx,
		resource.WithAttributes(
			semconv.ServiceName(cfg.ServiceName),
			semconv.ServiceVersion(cfg.ServiceVersion),
			semconv.DeploymentEnvironment(cfg.Environment),
		),
	)
	if err != nil {
		return nil, err
	}

	t := &Telemetry{logger: cfg.Logger}

	// --- Trace Provider ---
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint != "" {
		insecure := os.Getenv("OTEL_EXPORTER_OTLP_INSECURE") == "true"

		// Parse endpoint - strip scheme if present
		host := endpoint
		if strings.HasPrefix(host, "https://") {
			host = strings.TrimPrefix(host, "https://")
		} else if strings.HasPrefix(host, "http://") {
			host = strings.TrimPrefix(host, "http://")
			insecure = true
		}

		opts := []otlptracehttp.Option{
			otlptracehttp.WithEndpoint(host),
		}

		if insecure {
			// Local development: plain HTTP, no auth
			opts = append(opts, otlptracehttp.WithInsecure())
		} else {
			// Production: HTTPS with SigV4 for OpenSearch Ingestion
			sigV4Client, err := newSigV4Client(ctx)
			if err != nil {
				cfg.Logger.Error("failed to create SigV4 HTTP client, falling back to default", zap.Error(err))
			} else {
				opts = append(opts, otlptracehttp.WithHTTPClient(sigV4Client))
				cfg.Logger.Info("OTel exporter using SigV4 authentication for OpenSearch Ingestion")
			}
		}

		exporter, err := otlptracehttp.New(ctx, opts...)
		if err != nil {
			return nil, err
		}

		t.traceProvider = sdktrace.NewTracerProvider(
			sdktrace.WithBatcher(exporter),
			sdktrace.WithResource(res),
		)
		cfg.Logger.Info("OTel tracing enabled",
			zap.String("endpoint", host),
			zap.Bool("insecure", insecure))
	} else {
		// No-op: create a TracerProvider with no exporter so otelchi spans are silently dropped
		t.traceProvider = sdktrace.NewTracerProvider(
			sdktrace.WithResource(res),
		)
		cfg.Logger.Warn("OTEL_EXPORTER_OTLP_ENDPOINT not set, tracing disabled (spans will be dropped)")
	}

	otel.SetTracerProvider(t.traceProvider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	// --- Meter Provider ---
	metricExp, err := stdoutmetric.New()
	if err != nil {
		return nil, err
	}

	t.meterProvider = sdkmetric.NewMeterProvider(
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(metricExp)),
		sdkmetric.WithResource(res),
	)
	otel.SetMeterProvider(t.meterProvider)

	return t, nil
}

// newSigV4Client creates an HTTP client that signs requests with SigV4 for the "osis" service.
func newSigV4Client(ctx context.Context) (*http.Client, error) {
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}

	region := awsCfg.Region
	if region == "" {
		region = os.Getenv("AWS_REGION")
	}
	if region == "" {
		region = "us-east-1"
	}

	return &http.Client{
		Transport: &sigV4RoundTripper{
			base:   http.DefaultTransport,
			signer: v4.NewSigner(),
			creds:  awsCfg.Credentials,
			region: region,
		},
	}, nil
}

// Shutdown gracefully flushes and shuts down trace and metric providers.
func (t *Telemetry) Shutdown() {
	ctx := context.Background()
	if t.traceProvider != nil {
		if err := t.traceProvider.Shutdown(ctx); err != nil {
			t.logger.Error("error shutting down trace provider", zap.Error(err))
		}
	}
	if t.meterProvider != nil {
		if err := t.meterProvider.Shutdown(ctx); err != nil {
			t.logger.Error("error shutting down meter provider", zap.Error(err))
		}
	}
}
