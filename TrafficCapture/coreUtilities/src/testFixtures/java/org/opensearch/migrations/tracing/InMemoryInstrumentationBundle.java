package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.Getter;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InMemoryInstrumentationBundle implements AutoCloseable {

    public static final Duration DEFAULT_COLLECTION_PERIOD = Duration.ofMillis(1);
    private static final int MIN_MILLIS_TO_WAIT_FOR_FINISH = 10;

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

    @Getter
    public final OpenTelemetrySdk openTelemetrySdk;
    private final InMemorySpanExporter testSpanExporter;
    private final LastMetricsExporter testMetricExporter;
    private final PeriodicMetricReader periodicMetricReader;
    private final Duration collectionPeriod;
    private boolean alreadyWaitedForMetrics;

    public InMemoryInstrumentationBundle(boolean collectTraces,
                                         boolean collectMetrics) {
        this(collectTraces ? InMemorySpanExporter.create() : null,
                collectMetrics ? new LastMetricsExporter() : null);
    }

    public InMemoryInstrumentationBundle(InMemorySpanExporter testSpanExporter,
                                         LastMetricsExporter testMetricExporter) {
        this(testSpanExporter, testMetricExporter, DEFAULT_COLLECTION_PERIOD);
    }

    public InMemoryInstrumentationBundle(InMemorySpanExporter testSpanExporter,
                                         LastMetricsExporter testMetricExporter,
                                         Duration collectionPeriod) {
        this.testSpanExporter = testSpanExporter;
        this.testMetricExporter = testMetricExporter;
        this.collectionPeriod = collectionPeriod;

        var otelBuilder = OpenTelemetrySdk.builder();
        if (testSpanExporter != null) {
            otelBuilder = otelBuilder.setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(testSpanExporter)).build());
        }
        if (testMetricExporter != null) {
            this.periodicMetricReader = PeriodicMetricReader.builder(testMetricExporter)
                    .setInterval(Duration.ofMillis(collectionPeriod.toMillis()))
                    .build();
            otelBuilder = otelBuilder.setMeterProvider(SdkMeterProvider.builder()
                    .registerMetricReader(periodicMetricReader)
                    .build());
        } else {
            this.periodicMetricReader = null;
        }
        openTelemetrySdk = otelBuilder.build();
    }

    public List<SpanData> getFinishedSpans() {
        if (testSpanExporter == null) {
            throw new IllegalStateException("Metrics collector was not configured");
        }
        return testSpanExporter.getFinishedSpanItems();
    }

    /**
     * Waits double the collectionPeriod time (once) before returning the collected metrics
     * @return
     */
    @SneakyThrows
    public Collection<MetricData> getFinishedMetrics() {
        if (testMetricExporter == null) {
            throw new IllegalStateException("Metrics collector was not configured");
        }
        if (!alreadyWaitedForMetrics) {
            Thread.sleep(Math.max(collectionPeriod.toMillis() * 2, MIN_MILLIS_TO_WAIT_FOR_FINISH));
            alreadyWaitedForMetrics = true;
        }
        return testMetricExporter.getFinishedMetricItems();
    }

    @Override
    public void close() {
        Optional.ofNullable(testMetricExporter).ifPresent(me -> {
            try {
                periodicMetricReader.close();
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
            me.close();
        });
        Optional.ofNullable(testSpanExporter).ifPresent(te -> {
            te.close();
            te.reset();
        });
    }

}
