package org.opensearch.migrations.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import lombok.NonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class RootOtelContext implements IRootOtelContext {
    private final OpenTelemetry openTelemetryImpl;

    public static OpenTelemetry initializeOpenTelemetryForCollector(@NonNull String collectorEndpoint,
                                                                    @NonNull String serviceName) {
        var serviceResource = Resource.getDefault().toBuilder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .build();

        final var spanProcessor = BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder()
                        .setEndpoint(collectorEndpoint)
                        .setTimeout(2, TimeUnit.SECONDS)
                        .build())
                .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                .build();
        final var metricReader = PeriodicMetricReader.builder(OtlpGrpcMetricExporter.builder()
                        .setEndpoint(collectorEndpoint)
                        // see https://opentelemetry.io/docs/specs/otel/metrics/sdk_exporters/prometheus/
                        // "A Prometheus Exporter MUST only support Cumulative Temporality."
                        //.setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
                        .build())
                .setInterval(Duration.ofMillis(1000))
                .build();
        final var logProcessor = BatchLogRecordProcessor.builder(OtlpGrpcLogRecordExporter.builder()
                        .setEndpoint(collectorEndpoint)
                        .build())
                .build();

        var openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().setResource(serviceResource)
                        .addSpanProcessor(spanProcessor).build())
                .setMeterProvider(SdkMeterProvider.builder().setResource(serviceResource)
                        .registerMetricReader(metricReader).build())
                .setLoggerProvider(SdkLoggerProvider.builder().setResource(serviceResource)
                        .addLogRecordProcessor(logProcessor).build())
                .build();

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));
        return openTelemetrySdk;
    }

    public static OpenTelemetry initializeOpenTelemetry(String collectorEndpoint, String serviceName) {
        return Optional.ofNullable(collectorEndpoint)
                .map(endpoint -> initializeOpenTelemetryForCollector(endpoint, serviceName))
                .orElse(OpenTelemetrySdk.builder().build());
    }


    public RootOtelContext() {
        this(null);
    }

    public RootOtelContext(String collectorEndpoint, String serviceName) {
        this(initializeOpenTelemetry(collectorEndpoint, serviceName));
    }

    public RootOtelContext(OpenTelemetry sdk) {
        openTelemetryImpl = sdk != null ? sdk : initializeOpenTelemetry(null, null);
    }

    @Override
    public String getScopeName() {
        return "Root";
    }

    @Override
    public IRootOtelContext getEnclosingScope() {
        return null;
    }

    OpenTelemetry getOpenTelemetry() {
        return openTelemetryImpl;
    }

    @Override
    @NonNull
    public IRootOtelContext getRootInstrumentationScope() {
        return this;
    }

    @Override
    public Meter getMeterForScope(String scopeName) {
        return getOpenTelemetry().getMeter(scopeName);
    }

    public MeteringClosure buildMeterClosure(IInstrumentationAttributes ctx) {
        return new MeteringClosure(ctx, getMeterForScope(ctx.getScopeName()));
    }

    public MeteringClosureForStartTimes buildMeterClosure(IWithStartTimeAndAttributes ctx) {
        return new MeteringClosureForStartTimes(ctx, getOpenTelemetry().getMeter(ctx.getScopeName()));
    }

    @Override
    public AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder; // nothing more to do
    }

    private static Span buildSpanWithParent(SpanBuilder builder, Attributes attrs, Span parentSpan) {
        return Optional.ofNullable(parentSpan).map(p -> builder.setParent(Context.current().with(p)))
                .orElseGet(builder::setNoParent)
                .startSpan().setAllAttributes(attrs);
    }

    @Override
    public Span buildSpan(IInstrumentationAttributes enclosingScope, String scopeName, String spanName,
                          AttributesBuilder attributesBuilder) {
        var parentSpan = enclosingScope.getCurrentSpan();
        var spanBuilder = getOpenTelemetry().getTracer(scopeName).spanBuilder(spanName);
        return buildSpanWithParent(spanBuilder, getPopulatedAttributes(attributesBuilder), parentSpan);
    }
}
