package org.opensearch.migrations.trafficcapture.netty.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;
import org.opensearch.migrations.trafficcapture.tracing.RootOffloaderContext;

public class RootWireLoggingContext extends RootOffloaderContext implements IRootWireLoggingContext {
    public static final String SCOPE_NAME = "NettyCapture";
    @Getter
    Meter wireLoggingMeter;

    public RootWireLoggingContext(OpenTelemetry openTelemetry) {
        super(openTelemetry);
        wireLoggingMeter = super.getMeterForScope(SCOPE_NAME);
    }
}
