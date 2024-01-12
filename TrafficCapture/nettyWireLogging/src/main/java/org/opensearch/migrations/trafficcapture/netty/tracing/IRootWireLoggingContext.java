package org.opensearch.migrations.trafficcapture.netty.tracing;

import lombok.Getter;
import org.opensearch.migrations.tracing.IRootOtelContext;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;
import org.opensearch.migrations.trafficcapture.tracing.IRootOffloaderContext;

public interface IRootWireLoggingContext extends IRootOffloaderContext {

    WireCaptureContexts.ConnectionContext.MetricInstruments getConnectionInstruments();

    WireCaptureContexts.RequestContext.MetricInstruments getRequestInstruments();
    WireCaptureContexts.BlockingContext.MetricInstruments getBlockingInstruments();
    WireCaptureContexts.WaitingForResponseContext.MetricInstruments getWaitingForResponseInstruments();
    WireCaptureContexts.ResponseContext.MetricInstruments getResponseInstruments();

    IWireCaptureContexts.ICapturingConnectionContext createConnectionContext(String channelKey, String nodeId);

}
