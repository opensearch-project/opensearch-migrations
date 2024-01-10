package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.time.Duration;
import java.time.Instant;

public class MeteringClosureForStartTimes<S extends IInstrumentConstructor> extends MeteringClosure<S> {

    MeteringClosureForStartTimes(IWithStartTimeAndAttributes<S> ctx) {
        super(ctx);
    }

    public IWithStartTimeAndAttributes<S> getContext() {
        return (IWithStartTimeAndAttributes<S>) ctx;
    }

    public void meterHistogramMicros(DoubleHistogram histogram, Duration between, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, between.toNanos()*1000, attributesBuilder);
    }

    public void meterHistogramMillis(DoubleHistogram histogram, Duration between, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, between.toMillis(), attributesBuilder);
    }

    public void meterHistogram(DoubleHistogram h, long value, AttributesBuilder attributesBuilder) {
        if (ctx == null) {
            return;
        }
        try (var scope = new NullableExemplarScope(ctx.getCurrentSpan())) {
            h.record(value);
        }
    }

    public void meterHistogramMillis(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, Duration.between(getContext().getStartTime(), Instant.now()).toMillis(),
                attributesBuilder);
    }

    public void meterHistogramMicros(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram,
                Duration.between(getContext().getStartTime(), Instant.now()).toNanos()*1000, attributesBuilder);
    }
}
