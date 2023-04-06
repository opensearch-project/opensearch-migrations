package org.opensearch.migrations.trafficcapture;

public interface ConnectionCaptureFactory {
    public IChannelConnectionCaptureOffloader createPipe();
}
