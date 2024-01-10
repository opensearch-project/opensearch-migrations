package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;

public interface IInstrumentConstructor<S extends IInstrumentConstructor> {
    Span buildSpan(IInstrumentationAttributes<S> enclosingScope, String scopeName, String spanName,
                   AttributesBuilder attributesBuilder);
    MeteringClosure<S> buildSimpleMeterClosure(IInstrumentationAttributes<S> context);
    MeteringClosureForStartTimes<S> buildMeterClosure(IWithStartTimeAndAttributes<S> context);
}
