package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import org.opensearch.migrations.tracing.RootOtelContext;

public class RootOffloaderContext extends RootOtelContext {
    public static final String OFFLOADER_SCOPE_NAME = "Offloader";
    public final ConnectionContext.MetricInstruments connectionInstruments;

    public RootOffloaderContext(OpenTelemetry openTelemetry) {
        this(openTelemetry, OFFLOADER_SCOPE_NAME);
    }

    public RootOffloaderContext(OpenTelemetry openTelemetry, String scopeName) {
        super(scopeName, openTelemetry);
        var meter = openTelemetry.getMeterProvider().get(scopeName);
        connectionInstruments = new ConnectionContext.MetricInstruments(meter, scopeName);
    }
}
