package org.opensearch.migrations.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.Getter;

import java.time.Duration;
import java.util.Optional;

public class TestContext implements IInstrumentationAttributes {
    @Getter
    public IInstrumentConstructor rootInstrumentationScope;
    @Getter
    public final InMemorySpanExporter testSpanExporter;
    @Getter
    public final InMemoryMetricExporter testMetricExporter;

    public static TestContext withTracking() {
        return new TestContext(InMemorySpanExporter.create(), InMemoryMetricExporter.create());
    }

    public static TestContext noTracking() {
        return new TestContext(null, null);
    }

    public TestContext(InMemorySpanExporter testSpanExporter, InMemoryMetricExporter testMetricExporter) {
        this.testSpanExporter = testSpanExporter;
        this.testMetricExporter = testMetricExporter;

        var otelBuilder = OpenTelemetrySdk.builder();
        if (testSpanExporter != null) {
            otelBuilder = otelBuilder.setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(testSpanExporter)).build());
        }
        if (testMetricExporter != null) {
            otelBuilder = otelBuilder.setMeterProvider(SdkMeterProvider.builder()
                    .registerMetricReader(PeriodicMetricReader.builder(testMetricExporter)
                            .setInterval(Duration.ofMillis(100))
                            .build())
                    .build());
        }
        var openTel = otelBuilder.build();
        rootInstrumentationScope = new RootOtelContext(openTel);
    }

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return null;
    }

}
