package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.MeterProvider;

public interface IRootOtelContext extends IInstrumentationAttributes, IInstrumentConstructor {
    MeterProvider getMeterProvider();
}
