package org.opensearch.migrations.tracing;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CommonScopedMetricInstruments extends CommonMetricInstruments {
    final LongCounter contextCounter;
    final DoubleHistogram contextDuration;

    public CommonScopedMetricInstruments(Meter meter, String activityName) {
        this(meter, activityName, null);
    }

    public CommonScopedMetricInstruments(
        Meter meter,
        String activityName,
        double firstBucketSize,
        double lastBucketCeiling
    ) {
        this(meter, activityName, getBuckets(activityName, firstBucketSize, lastBucketCeiling));
    }

    private static List<Double> getBuckets(String activityName, double firstBucketSize, double lastBucketCeiling) {
        var buckets = getExponentialBucketsBetween(firstBucketSize, lastBucketCeiling, 2.0);
        log.atInfo()
            .setMessage(
                () -> "Setting buckets for "
                    + activityName
                    + " to "
                    + buckets.stream().map(x -> "" + x).collect(Collectors.joining(",", "[", "]"))
            )
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

    public CommonScopedMetricInstruments(Meter meter, String activityName, List<Double> buckets) {
        super(meter, activityName);
        contextCounter = meter.counterBuilder(activityName + "Count").build();
        var durationBuilder = meter.histogramBuilder(activityName + "Duration").setUnit("ms");
        if (buckets != null) {
            durationBuilder = durationBuilder.setExplicitBucketBoundariesAdvice(buckets);
        }
        contextDuration = durationBuilder.build();
    }
}
