package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;

import java.time.Duration;
import java.time.Instant;

public class MeteringClosureForStartTimes extends MeteringClosure {

    MeteringClosureForStartTimes(IWithStartTimeAndAttributes ctx, Meter meter) {
        super(ctx, meter);
    }

    public IWithStartTimeAndAttributes getContext() {
        return (IWithStartTimeAndAttributes) ctx;
    }

    public void meterHistogramMicros(String eventName, Duration between, AttributesBuilder attributesBuilder) {
        meterHistogram(eventName, "us", between.toNanos()*1000, attributesBuilder);
    }

    public void meterHistogramMillis(String eventName, Duration between, AttributesBuilder attributesBuilder) {
        meterHistogram(eventName, "ms", between.toMillis(), attributesBuilder);
    }

    public void meterHistogram(String eventName, String units, long value, AttributesBuilder attributesBuilder) {
        if (ctx == null) {
            return;
        }
        meter.histogramBuilder(eventName)
                .ofLongs()
                .setUnit(units)
                .build().record(value, ctx.getPopulatedAttributesBuilder(attributesBuilder)
                        .put("labelName", eventName)
                        .build());
    }

    public void meterHistogramMillis(String eventName, AttributesBuilder attributesBuilder) {
        meterHistogram(eventName, "ms", Duration.between(getContext().getStartTime(), Instant.now()).toMillis(),
                attributesBuilder);
    }

    public void meterHistogramMicros(String eventName, AttributesBuilder attributesBuilder) {
        meterHistogram(eventName, "us",
                Duration.between(getContext().getStartTime(), Instant.now()).toNanos()*1000, attributesBuilder);
    }
}
