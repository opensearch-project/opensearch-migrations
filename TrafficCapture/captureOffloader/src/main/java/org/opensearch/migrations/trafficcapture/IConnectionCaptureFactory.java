package org.opensearch.migrations.trafficcapture;

import java.io.IOException;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

public interface IConnectionCaptureFactory<T> {
    IChannelConnectionCaptureSerializer<T> createOffloader(IConnectionContext ctx) throws IOException;
}
