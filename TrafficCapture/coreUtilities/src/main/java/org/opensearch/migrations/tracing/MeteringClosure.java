package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
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
        meterIncrementEvent(eventName, increment, meter.counterBuilder(eventName).build(), attributesBuilder);
    }

    public void meterIncrementEvent(String eventName, long increment, LongCounter c,
                                    AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(ctx.getCurrentSpan())) {
            c.add(increment, ctx.getPopulatedAttributesBuilder(attributesBuilder)
                            .put("labelName", eventName)
                            .build());
        }
    }

    public void meterDeltaEvent(String eventName, long delta, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(ctx.getCurrentSpan())) {
            log.warn("Log with or without trace context?");
            meter.upDownCounterBuilder(eventName)
                    .build().add(delta, ctx.getPopulatedAttributesBuilder(attributesBuilder)
                            .put("labelName", eventName)
                            .build());
        }
    }
}
