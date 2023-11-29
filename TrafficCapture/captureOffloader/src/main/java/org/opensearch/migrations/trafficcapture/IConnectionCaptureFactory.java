package org.opensearch.migrations.trafficcapture;

import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.io.IOException;

public interface IConnectionCaptureFactory<T> {
    IChannelConnectionCaptureSerializer<T> createOffloader(ConnectionContext ctx, String connectionId) throws IOException;
}
