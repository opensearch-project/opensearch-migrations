package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

@Slf4j
public class CommonScopedMetricInstruments {
    public final LongCounter contextCounter;
    public final DoubleHistogram contextDuration;
    public CommonScopedMetricInstruments(MeterProvider meterProvider, String scopeName, String activityName) {
        var meter = meterProvider.get(scopeName);
        contextCounter = meter
                .counterBuilder(activityName + "Count").build();
        contextDuration = meter
                .histogramBuilder(activityName + "Duration")
                .setUnit("ms")
                .build();
    }

    public CommonScopedMetricInstruments(MeterProvider meterProvider, String scopeName, String activityName,
                                         double firstBucketSize, double lastBucketCeiling) {
        var meter = meterProvider.get(scopeName);
        contextCounter = meter
                .counterBuilder(activityName + "Count").build();
        double[] bucketBoundary = new double[]{firstBucketSize};
        var buckets = DoubleStream.generate(()->{
            var tmp = bucketBoundary[0];
            bucketBoundary[0] *= 2.0;
            return tmp;
        }).takeWhile(v->v<=lastBucketCeiling).boxed().collect(Collectors.toList());
        log.atInfo().setMessage(()->"Setting buckets for "+scopeName+":"+activityName+" to "+
                buckets.stream().map(x->""+x).collect(Collectors.joining(",","[","]"))).log();
        contextDuration = meter
                .histogramBuilder(activityName + "Duration")
                .setUnit("ms")
                .setExplicitBucketBoundariesAdvice(buckets)
                .build();
    }
}
