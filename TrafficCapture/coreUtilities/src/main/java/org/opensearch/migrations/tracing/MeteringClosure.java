package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import lombok.AllArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@AllArgsConstructor
public class MeteringClosure {
    public final IInstrumentationAttributes ctx;
    public final Meter meter;

    public void meterIncrementEvent(String eventName, AttributesBuilder attributesBuilder) {
        meterIncrementEvent(eventName, 1, attributesBuilder);
    }

    public void meterIncrementEvent(String eventName, long increment, AttributesBuilder attributesBuilder) {
        if (ctx == null) {
            return;
        }
        meter.counterBuilder(eventName)
                .build().add(increment, ctx.getPopulatedAttributesBuilder(attributesBuilder)
                        .put("labelName", eventName)
                        .build());
    }

    public void meterDeltaEvent(String eventName, long delta, AttributesBuilder attributesBuilder) {
        meter.upDownCounterBuilder(eventName)
                .build().add(delta, ctx.getPopulatedAttributesBuilder(attributesBuilder)
                        .put("labelName", eventName)
                        .build());
    }
}
