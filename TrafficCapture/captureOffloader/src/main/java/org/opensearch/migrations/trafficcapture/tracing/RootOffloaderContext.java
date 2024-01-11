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
        super(openTelemetry);
        var meterProvider = openTelemetry.getMeterProvider();
        connectionInstruments = new ConnectionContext.MetricInstruments(meterProvider, OFFLOADER_SCOPE_NAME);
    }
}
