package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
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

    public void meterIncrementEvent(LongCounter c, AttributesBuilder attributesBuilder) {
        meterIncrementEvent(c, 1, attributesBuilder);
    }

    public void meterIncrementEvent(LongCounter c, long increment, AttributesBuilder attributesBuilder) {
        if (ctx == null) {
            return;
        }
        try (var scope = new NullableExemplarScope(ctx.getCurrentSpan())) {
            c.add(increment);
            //            c.add(increment, ctx.getPopulatedAttributesBuilder(attributesBuilder)
            //                            .put("labelName", eventName)
            //                            .build());
        }
    }

    public void meterDeltaEvent(LongUpDownCounter c, long delta,
                                AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(ctx.getCurrentSpan())) {
            c.add(delta);
//            c.add(delta, ctx.getPopulatedAttributesBuilder(attributesBuilder)
//                            .put("labelName", eventName)
//                            .build());
        }
    }
}
