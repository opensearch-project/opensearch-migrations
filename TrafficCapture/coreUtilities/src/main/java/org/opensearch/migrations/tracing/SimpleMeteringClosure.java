package org.opensearch.migrations.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class SimpleMeteringClosure {
    public final Meter meter;

    public SimpleMeteringClosure(String scopeName) {
        meter = GlobalOpenTelemetry.getMeter(scopeName);
    }

    public void meterIncrementEvent(IInstrumentationAttributes ctx, String eventName) {
        meterIncrementEvent(ctx, eventName, 1);
    }

    public void meterIncrementEvent(IInstrumentationAttributes ctx, String eventName, long increment) {
        if (ctx == null) {
            return;
        }
        meter.counterBuilder(eventName)
                .build().add(increment, ctx.getPopulatedAttributesBuilder()
                        .put("labelName", eventName)
                        .build());
    }

    public void meterDeltaEvent(IInstrumentationAttributes ctx, String eventName, long delta) {
        if (ctx == null) {
            return;
        }
        meter.upDownCounterBuilder(eventName)
                .build().add(delta, ctx.getPopulatedAttributesBuilder()
                        .put("labelName", eventName)
                        .build());
    }

    public <T extends IInstrumentationAttributes & IWithStartTime> void meterHistogramMillis(T ctx, String eventName) {
        meterHistogram(ctx, eventName, "ms", Duration.between(ctx.getStartTime(), Instant.now()).toMillis());
    }

    public <T extends IInstrumentationAttributes & IWithStartTime> void meterHistogramMicros(T ctx, String eventName) {
        meterHistogram(ctx, eventName, "us", Duration.between(ctx.getStartTime(), Instant.now()).toNanos()*1000);
    }

    public void meterHistogramMillis(IInstrumentationAttributes ctx, String eventName, Duration between) {
        meterHistogram(ctx, eventName, "ms", between.toMillis());
    }

    public void meterHistogramMicros(IInstrumentationAttributes ctx, String eventName, Duration between) {
        meterHistogram(ctx, eventName, "us", between.toNanos()*1000);
    }

    public void meterHistogram(IInstrumentationAttributes ctx, String eventName, String units, long value) {
        if (ctx == null) {
            return;
        }
        meter.histogramBuilder(eventName)
                .ofLongs()
                .setUnit(units)
                .build().record(value, ctx.getPopulatedAttributesBuilder()
                        .put("labelName", eventName)
                        .build());
    }
}
