package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;


@Slf4j
public class CommonScopedMetricInstruments {
    final LongCounter contextCounter;
    final LongCounter exceptionCounter;
    final DoubleHistogram contextDuration;

    public CommonScopedMetricInstruments(Meter meter, String activityName) {
        this(meter, activityName, null);
    }

    public CommonScopedMetricInstruments(Meter meter, String activityName,
                                         double firstBucketSize, double lastBucketCeiling) {
        this(meter, activityName, getBuckets(activityName, firstBucketSize, lastBucketCeiling));
    }

    private static List<Double> getBuckets(String activityName,
                                           double firstBucketSize, double lastBucketCeiling) {
        double[] bucketBoundary = new double[]{firstBucketSize};
        var buckets = DoubleStream.generate(() -> {
            var tmp = bucketBoundary[0];
            bucketBoundary[0] *= 2.0;
            return tmp;
        }).takeWhile(v -> v <= lastBucketCeiling).boxed().collect(Collectors.toList());
        log.atInfo().setMessage(() -> "Setting buckets for " + activityName + " to " +
                buckets.stream().map(x -> "" + x).collect(Collectors.joining(",", "[", "]"))).log();
        return buckets;
    }

    public CommonScopedMetricInstruments(Meter meter, String activityName, List<Double> buckets) {
        contextCounter = meter
                .counterBuilder(activityName + "Count").build();
        exceptionCounter = meter
                .counterBuilder(activityName + "ExceptionCount").build();
        var durationBuilder = meter
                .histogramBuilder(activityName + "Duration")
                .setUnit("ms");
        if (buckets != null) {
            durationBuilder = durationBuilder.setExplicitBucketBoundariesAdvice(buckets);
        }
        contextDuration = durationBuilder.build();
    }
}
