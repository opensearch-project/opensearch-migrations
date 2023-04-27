package org.opensearch.migrations.trafficcapture;

public interface IChannelConnectionCaptureSerializer extends IChannelConnectionCaptureListener {

    default void setRequestMethod(String requestMethod) {
    }
}
