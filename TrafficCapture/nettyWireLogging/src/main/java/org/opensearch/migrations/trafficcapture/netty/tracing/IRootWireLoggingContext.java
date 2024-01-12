package org.opensearch.migrations.trafficcapture.netty.tracing;

import lombok.Getter;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootWireLoggingContext extends IRootOtelContext {
    WireCaptureContexts.RequestContext.MetricInstruments getHttpRequestInstruments();
    WireCaptureContexts.BlockingContext.MetricInstruments getBlockingInstruments();
    WireCaptureContexts.WaitingForResponseContext.MetricInstruments getWaitingForResponseInstruments();
    WireCaptureContexts.ResponseContext.MetricInstruments getResponseInstruments();

}
