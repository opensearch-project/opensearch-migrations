package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.Getter;
import lombok.NonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class InMemoryInstrumentationBundle implements AutoCloseable {

    public static class LastMetricsExporter implements MetricExporter {
        private final Queue<MetricData> finishedMetricItems = new ConcurrentLinkedQueue<>();
        boolean isStopped;

        public List<MetricData> getFinishedMetricItems() {
            return Collections.unmodifiableList(new ArrayList<>(finishedMetricItems));
        }

        @Override
        public CompletableResultCode export(@NonNull Collection<MetricData> metrics) {
            if (isStopped) {
                return CompletableResultCode.ofFailure();
            }
            finishedMetricItems.clear();
            finishedMetricItems.addAll(metrics);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            isStopped = true;
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(@NonNull InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }
    }

    public final OpenTelemetrySdk openTelemetrySdk;
    public final InMemorySpanExporter testSpanExporter;
    public final LastMetricsExporter testMetricExporter;

    public InMemoryInstrumentationBundle(boolean collectTraces,
                                         boolean collectMetrics) {
        this(collectTraces ? InMemorySpanExporter.create() : null,
                collectMetrics ? new LastMetricsExporter() : null);
    }

    public InMemoryInstrumentationBundle(InMemorySpanExporter testSpanExporter,
                                         LastMetricsExporter testMetricExporter) {
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

    @Override
    public void close() {
        Optional.ofNullable(testMetricExporter).ifPresent(MetricExporter::close);
        Optional.ofNullable(testSpanExporter).ifPresent(te -> {
            te.close();
            te.reset();
        });
    }

}
