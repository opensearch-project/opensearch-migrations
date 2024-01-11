package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;

public interface IRootOtelContext<S extends IInstrumentConstructor> extends IInstrumentationAttributes<S>, IInstrumentConstructor {
    MeterProvider getMeterProvider();
    default Meter getMeterForScope(String scopeName) {
        return getMeterProvider().get(scopeName);
    }
}
