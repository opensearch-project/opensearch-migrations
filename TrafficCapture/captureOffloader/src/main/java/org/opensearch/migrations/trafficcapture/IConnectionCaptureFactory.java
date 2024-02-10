package org.opensearch.migrations.trafficcapture;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import java.io.IOException;

public interface IConnectionCaptureFactory<T> {
    IChannelConnectionCaptureSerializer<T> createOffloader(IConnectionContext ctx) throws IOException;
}
