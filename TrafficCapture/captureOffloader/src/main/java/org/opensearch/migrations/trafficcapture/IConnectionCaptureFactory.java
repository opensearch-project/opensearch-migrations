package org.opensearch.migrations.trafficcapture;

import java.io.IOException;

public interface IConnectionCaptureFactory {
    IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException;
}
