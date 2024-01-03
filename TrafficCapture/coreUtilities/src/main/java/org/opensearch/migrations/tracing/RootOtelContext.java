package org.opensearch.migrations.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
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

public class RootOtelContext implements IInstrumentationAttributes, IInstrumentConstructor {
    private final OpenTelemetry openTelemetryImpl;

    public static OpenTelemetry initializeOpenTelemetry(String serviceName, String collectorEndpoint) {
        var serviceResource = Resource.getDefault().toBuilder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .build();

        var openTelemetrySdk =
                OpenTelemetrySdk.builder()
                        .setLoggerProvider(
                                SdkLoggerProvider.builder()
                                        .setResource(serviceResource)
                                        .addLogRecordProcessor(
                                                BatchLogRecordProcessor.builder(
                                                                OtlpGrpcLogRecordExporter.builder()
                                                                        .setEndpoint(collectorEndpoint)
                                                                        .build())
                                                        .build())
                                        .build())
                        .setTracerProvider(
                                SdkTracerProvider.builder()
                                        .setResource(serviceResource)
                                        .addSpanProcessor(
                                                BatchSpanProcessor.builder(
                                                                OtlpGrpcSpanExporter.builder()
                                                                        .setEndpoint(collectorEndpoint)
                                                                        .setTimeout(2, TimeUnit.SECONDS)
                                                                        .build())
                                                        .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                                                        .build())
                                        .build())
                        .setMeterProvider(
                                SdkMeterProvider.builder()
                                        .setResource(serviceResource)
                                        .registerMetricReader(
                                                PeriodicMetricReader.builder(
                                                                OtlpGrpcMetricExporter.builder()
                                                                        .setEndpoint(collectorEndpoint)
                                                                        .build())
                                                        .setInterval(Duration.ofMillis(1000))
                                                        .build())
                                        .build())
                        .build();

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));
        return openTelemetrySdk;
    }

    public RootOtelContext() {
        this(null, null);
    }

    public RootOtelContext(String collectorEndpoint, String serviceName) {
        this(Optional.ofNullable(collectorEndpoint)
                .map(endpoint-> initializeOpenTelemetry(serviceName, endpoint))
                .orElse(OpenTelemetrySdk.builder().build()));
    }

    public RootOtelContext(OpenTelemetry sdk) {
        openTelemetryImpl = sdk;
    }

    @Override
    public String getScopeName() {
        return "Root";
    }

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return null;
    }

    OpenTelemetry getOpenTelemetry() {
        return openTelemetryImpl;
    }

    @Override
    @NonNull
    public IInstrumentConstructor getRootInstrumentationScope() {
        return this;
    }

    public MeteringClosure buildSimpleMeter(IInstrumentationAttributes ctx) {
        return new MeteringClosure(ctx, getOpenTelemetry().getMeter(ctx.getScopeName()));
    }

    public MeteringClosureForStartTimes buildMeter(IWithStartTimeAndAttributes ctx) {
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
