package org.opensearch.migrations.trafficcapture;

import java.io.IOException;

public interface IConnectionCaptureFactory<T> {
    IChannelConnectionCaptureSerializer<T> createOffloader(String connectionId) throws IOException;
}
