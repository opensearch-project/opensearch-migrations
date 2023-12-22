package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.Meter;
import lombok.AllArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@AllArgsConstructor
public class SimpleMeteringClosure<T extends IInstrumentationAttributes & IWithStartTime> {
    public final T ctx;
    public final Meter meter;

    public void meterIncrementEvent(String eventName) {
        meterIncrementEvent(eventName, 1);
    }

    public void meterIncrementEvent(String eventName, long increment) {
        if (ctx == null) {
            return;
        }
        meter.counterBuilder(eventName)
                .build().add(increment, ctx.getPopulatedAttributesBuilder()
                        .put("labelName", eventName)
                        .build());
    }

    public void meterDeltaEvent(String eventName, long delta) {
        meter.upDownCounterBuilder(eventName)
                .build().add(delta, ctx.getPopulatedAttributesBuilder()
                        .put("labelName", eventName)
                        .build());
    }

    public void meterHistogramMicros(String eventName, Duration between) {
        meterHistogram(eventName, "us", between.toNanos()*1000);
    }

    public void meterHistogramMillis(String eventName, Duration between) {
        meterHistogram(eventName, "ms", between.toMillis());
    }

    public void meterHistogram(String eventName, String units, long value) {
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

    public void meterHistogramMillis(String eventName) {
        meterHistogram(eventName, "ms",
                Duration.between(ctx.getStartTime(), Instant.now()).toMillis());
    }

    public void meterHistogramMicros(String eventName) {
        meterHistogram(eventName, "us",
                Duration.between(ctx.getStartTime(), Instant.now()).toNanos()*1000);
    }

}
