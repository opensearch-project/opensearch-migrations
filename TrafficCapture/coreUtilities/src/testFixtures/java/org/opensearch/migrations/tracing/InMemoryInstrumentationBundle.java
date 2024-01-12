package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.Getter;

import java.time.Duration;

@Getter
public class InMemoryInstrumentationBundle {
    public final OpenTelemetrySdk openTelemetrySdk;
    public final InMemorySpanExporter testSpanExporter;
    public final InMemoryMetricExporter testMetricExporter;

    public InMemoryInstrumentationBundle(InMemorySpanExporter testSpanExporter,
                                         InMemoryMetricExporter testMetricExporter) {
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
        openTelemetrySdk = otelBuilder.build();
    }
}
