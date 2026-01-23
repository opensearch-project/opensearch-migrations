package org.opensearch.migrations.tracing;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.Getter;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InMemoryInstrumentationBundle implements AutoCloseable {

    @Getter
    public final OpenTelemetrySdk openTelemetrySdk;
    private final InMemorySpanExporter testSpanExporter;
    private final InMemoryMetricReader testMetricReader;

    public InMemoryInstrumentationBundle(boolean collectTraces, boolean collectMetrics) {
        this(
            collectTraces ? InMemorySpanExporter.create() : null,
            collectMetrics ? InMemoryMetricReader.create() : null
        );
    }

    public InMemoryInstrumentationBundle(InMemorySpanExporter testSpanExporter, InMemoryMetricReader testMetricReader) {
        this.testSpanExporter = testSpanExporter;
        this.testMetricReader = testMetricReader;

        var otelBuilder = OpenTelemetrySdk.builder();
        if (testSpanExporter != null) {
            otelBuilder = otelBuilder.setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(testSpanExporter)).build()
            );
        }
        if (testMetricReader != null) {
            otelBuilder = otelBuilder.setMeterProvider(
                SdkMeterProvider.builder().registerMetricReader(testMetricReader).build()
            );
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
        if (testMetricReader == null) {
            throw new IllegalStateException("Metrics collector was not configured");
        }
        testMetricReader.forceFlush();
        return testMetricReader.collectAllMetrics();
    }

    public static long getMetricValueOrZero(Collection<MetricData> metrics, String metricName) {
        return reduceMetricStreamToOptionalSum(
            metrics.stream()
                .filter(md -> md.getName().startsWith(metricName)))
            .orElse(0L);
    }

    public static Optional<Long> reduceMetricStreamToOptionalSum(Stream<MetricData> stream) {
        return stream.reduce((a, b) -> b)
            .flatMap(md -> md.getLongSumData().getPoints().stream().reduce((a, b) -> b).map(LongPointData::getValue));
    }

    public static long reduceMetricStreamToSum(Stream<MetricData> stream) {
        return reduceMetricStreamToOptionalSum(stream).orElse(-1L);
    }

    @Override
    public void close() {
        Optional.ofNullable(testMetricReader).ifPresent(me -> {
            try {
                me.close();
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
        });
        Optional.ofNullable(testSpanExporter).ifPresent(te -> {
            te.close();
            te.reset();
        });
    }

    public List<MetricData> getMetricsUntil(
        String metricName,
        IntStream sleepTimes,
        Predicate<List<MetricData>> untilPredicate
    ) {
        AtomicReference<List<MetricData>> matchingMetrics = new AtomicReference<>();
        sleepTimes.mapToObj(sleepAmount -> {
            matchingMetrics.set(
                getFinishedMetrics().stream().filter(md -> md.getName().equals(metricName)).collect(Collectors.toList())
            );
            if (untilPredicate.test(matchingMetrics.get())) {
                return true;
            } else {
                try {
                    log.atInfo()
                        .setMessage("Waiting {}ms because the last test for metrics from {} on {} did not satisfy the predicate")
                        .addArgument(sleepAmount)
                        .addArgument(metricName)
                        .addArgument(matchingMetrics::get)
                        .log();
                    Thread.sleep(sleepAmount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw Lombok.sneakyThrow(e);
                }
                return false;
            }
        }).takeWhile(x -> !x).forEach(b -> {});
        if (matchingMetrics.get() == null) {
            throw new NoSuchElementException("Could not find matching metrics.  Last metrics: " + matchingMetrics);
        } else {
            return matchingMetrics.get();
        }
    }
}
