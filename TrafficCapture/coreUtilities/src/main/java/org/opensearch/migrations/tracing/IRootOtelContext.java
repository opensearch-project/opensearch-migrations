package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.Meter;

public interface IRootOtelContext extends IInstrumentationAttributes<IRootOtelContext>, IInstrumentConstructor {
    Meter getMeterForScope(String scopeName);
}
