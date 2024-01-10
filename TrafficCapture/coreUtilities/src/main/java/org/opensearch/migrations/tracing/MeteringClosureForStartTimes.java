package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.time.Duration;
import java.time.Instant;

public class MeteringClosureForStartTimes extends MeteringClosure {

    public static LongHistogram makeHistogram(Meter meter, String eventName, String units, long value) {
        return meter.histogramBuilder(eventName)
                .ofLongs()
                .setUnit(units)
                .build();
    }

    MeteringClosureForStartTimes(IWithStartTimeAndAttributes ctx, Meter meter) {
        super(ctx, meter);
    }

    public IWithStartTimeAndAttributes getContext() {
        return (IWithStartTimeAndAttributes) ctx;
    }

    public void meterHistogramMicros(LongHistogram histogram, Duration between, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, between.toNanos()*1000, attributesBuilder);
    }

    public void meterHistogramMillis(LongHistogram histogram, Duration between, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, between.toMillis(), attributesBuilder);
    }

    public void meterHistogram(LongHistogram h, long value, AttributesBuilder attributesBuilder) {
        if (ctx == null) {
            return;
        }
        try (var scope = new NullableExemplarScope(ctx.getCurrentSpan())) {
            h.record(value);
//            h.record(value, ctx.getPopulatedAttributesBuilder(attributesBuilder)
//                            //.put("labelName", eventName)
//                            .build());
        }
    }

    public void meterHistogramMillis(LongHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, Duration.between(getContext().getStartTime(), Instant.now()).toMillis(),
                attributesBuilder);
    }

    public void meterHistogramMicros(LongHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram,
                Duration.between(getContext().getStartTime(), Instant.now()).toNanos()*1000, attributesBuilder);
    }
}
