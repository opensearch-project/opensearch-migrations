package org.opensearch.migrations.tracing;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CommonScopedMetricInstruments extends CommonMetricInstruments {

    public static final String DURATION_APPENDAGE = "Duration";

    /**
     * Bucket bounds in ms for every duration histogram: OpenTelemetry's defaults, extended from 10 s to 30 s so that
     * a slow tail is distinguishable from the overflow bucket rather than folded into it.
     */
    public static final List<Double> DURATION_BUCKETS_MS = List.of(
        0.0, 5.0, 10.0, 25.0, 50.0, 75.0, 100.0, 250.0, 500.0, 750.0,
        1_000.0, 2_500.0, 5_000.0, 7_500.0, 10_000.0, 15_000.0, 30_000.0
    );

    /**
     * Bounds for durations that legitimately run past 30 s: lifetimes, end-to-end lag, and waits on a slow peer.
     * Each added bound costs one series per histogram per process in Prometheus, so these are opted into.
     */
    public static final List<Double> LONG_DURATION_BUCKETS_MS = Stream.concat(
        DURATION_BUCKETS_MS.stream(),
        Stream.of(60_000.0, 120_000.0, 300_000.0, 600_000.0, 1_800_000.0, 3_600_000.0)
    ).toList();

    @AllArgsConstructor
    public static class ScopeLabels {
        public final String counter;
        public final String duration;
        public final String exception;
    }

    public static ScopeLabels fromActivityName(String activityName) {
        return new ScopeLabels(
            activityName + "Count",
            activityName + DURATION_APPENDAGE,
            CommonMetricInstruments.Labels.fromActivityName(activityName).exception
        );
    }

    public static ScopeLabels activityNameForTheCountMetric(String activityName) {
        return new ScopeLabels(
            activityName,
            activityName + DURATION_APPENDAGE,
            CommonMetricInstruments.Labels.fromActivityName(activityName).exception
        );
    }

    final LongCounter contextCounter;
    final DoubleHistogram contextDuration;

    public CommonScopedMetricInstruments(Meter meter, String activityName) {
        this(meter, fromActivityName(activityName));
    }

    public CommonScopedMetricInstruments(
        Meter meter,
        String activityName,
        double firstBucketSize,
        double lastBucketCeiling
    ) {
        this(meter, fromActivityName(activityName), getBuckets(firstBucketSize, lastBucketCeiling));
    }

    public CommonScopedMetricInstruments(Meter meter, String activityName, List<Double> buckets) {
        this(meter, fromActivityName(activityName), buckets);
    }

    public CommonScopedMetricInstruments(Meter meter, ScopeLabels stockMetricLabels) {
        this(meter, stockMetricLabels, DURATION_BUCKETS_MS);
    }

    public CommonScopedMetricInstruments(Meter meter, ScopeLabels stockMetricLabels, List<Double> buckets) {
        super(meter, new CommonMetricInstruments.Labels(stockMetricLabels.exception));
        contextCounter = meter.counterBuilder(stockMetricLabels.counter).build();
        var durationBuilder = meter.histogramBuilder(stockMetricLabels.duration).setUnit("ms");
        if (buckets != null) {
            durationBuilder = durationBuilder.setExplicitBucketBoundariesAdvice(buckets);
        }
        contextDuration = durationBuilder.build();
    }

    private static List<Double> getBuckets(double firstBucketSize, double lastBucketCeiling) {
        var buckets = getExponentialBucketsBetween(firstBucketSize, lastBucketCeiling);
        log.atTrace()
            .setMessage("Setting buckets to {}")
            .addArgument(() -> buckets.stream().map(x -> "" + x)
                .collect(Collectors.joining(",", "[", "]")))
            .log();
        return buckets;
    }

    private static List<Double> getExponentialBucketsBetween(double firstBucketSize, double lastBucketCeiling) {
        return getExponentialBucketsBetween(firstBucketSize, lastBucketCeiling, 2.0);
    }

    private static List<Double> getExponentialBucketsBetween(
        double firstBucketSize,
        double lastBucketCeiling,
        double rate
    ) {
        if (firstBucketSize <= 0) {
            throw new IllegalArgumentException("firstBucketSize value " + firstBucketSize + " must be > 0");
        }
        double[] bucketBoundary = new double[] { firstBucketSize };
        return DoubleStream.generate(() -> {
            var tmp = bucketBoundary[0];
            bucketBoundary[0] *= rate;
            return tmp;
        }).takeWhile(v -> v <= lastBucketCeiling).boxed().collect(Collectors.toList());
    }
}
