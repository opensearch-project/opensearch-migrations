package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import org.opensearch.migrations.tracing.RootOtelContext;

public class RootOffloaderContext extends RootOtelContext implements IRootOffloaderContext {
    public static final String SCOPE_NAME = "Offloader";
    @Getter
    public LongUpDownCounter activeConnectionsCounter;
    @Getter
    Meter offloaderMeter;

    public RootOffloaderContext(OpenTelemetry openTelemetry) {
        super(openTelemetry);
        offloaderMeter = super.getMeterForScope(SCOPE_NAME);
        activeConnectionsCounter = offloaderMeter.upDownCounterBuilder(ConnectionContext.ACTIVE_CONNECTION).build();
    }
}
