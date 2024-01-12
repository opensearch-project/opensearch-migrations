package org.opensearch.migrations.trafficcapture.netty.tracing;

import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import org.opensearch.migrations.trafficcapture.tracing.RootOffloaderContext;

public class RootWireLoggingContext extends RootOffloaderContext implements IRootWireLoggingContext {
    public static final String SCOPE_NAME = "NettyCapture";

    @Getter public final WireCaptureContexts.RequestContext.MetricInstruments httpRequestInstruments;
    @Getter public final WireCaptureContexts.BlockingContext.MetricInstruments blockingInstruments;
    @Getter public final WireCaptureContexts.WaitingForResponseContext.MetricInstruments waitingForResponseInstruments;
    @Getter public final WireCaptureContexts.ResponseContext.MetricInstruments responseInstruments;

    public RootWireLoggingContext(OpenTelemetry openTelemetry) {
        this(openTelemetry, SCOPE_NAME);
    }

    public RootWireLoggingContext(OpenTelemetry openTelemetry, String scopeName) {
        super(openTelemetry);
        var meter = this.getMeterProvider().get(scopeName);
        httpRequestInstruments = new WireCaptureContexts.RequestContext.MetricInstruments(meter);
        blockingInstruments = new WireCaptureContexts.BlockingContext.MetricInstruments(meter);
        waitingForResponseInstruments = new WireCaptureContexts.WaitingForResponseContext.MetricInstruments(meter);
        responseInstruments = new WireCaptureContexts.ResponseContext.MetricInstruments(meter);
    }
}
