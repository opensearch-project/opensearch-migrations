package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.Meter;

public interface IRootOtelContext extends IInstrumentationAttributes, IInstrumentConstructor {
    Meter getMeterForScope(String scopeName);
}
