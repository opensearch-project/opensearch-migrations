package org.opensearch.migrations.trafficcapture.netty.tracing;

import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import org.opensearch.migrations.tracing.RootOtelContext;

@Getter
public class RootWireLoggingContext extends RootOtelContext implements IRootWireLoggingContext {
    public static final String SCOPE_NAME = "NettyCapture";

    public final WireCaptureContexts.ConnectionContext.MetricInstruments connectionInstruments;
    public final WireCaptureContexts.RequestContext.MetricInstruments requestInstruments;
    public final WireCaptureContexts.BlockingContext.MetricInstruments blockingInstruments;
    public final WireCaptureContexts.WaitingForResponseContext.MetricInstruments waitingForResponseInstruments;
    public final WireCaptureContexts.ResponseContext.MetricInstruments responseInstruments;

    public RootWireLoggingContext(OpenTelemetry openTelemetry) {
        this(openTelemetry, SCOPE_NAME);
    }

    public RootWireLoggingContext(OpenTelemetry openTelemetry, String scopeName) {
        super(scopeName, openTelemetry);
        var meter = this.getMeterProvider().get(scopeName);
        connectionInstruments = WireCaptureContexts.ConnectionContext.makeMetrics(meter);
        requestInstruments = WireCaptureContexts.RequestContext.makeMetrics(meter);
        blockingInstruments = WireCaptureContexts.BlockingContext.makeMetrics(meter);
        waitingForResponseInstruments = WireCaptureContexts.WaitingForResponseContext.makeMetrics(meter);
        responseInstruments = WireCaptureContexts.ResponseContext.makeMetrics(meter);
    }

    @Override
    public IWireCaptureContexts.ICapturingConnectionContext createConnectionContext(String channelKey, String nodeId) {
        return new WireCaptureContexts.ConnectionContext(this, channelKey, nodeId);
    }
}
