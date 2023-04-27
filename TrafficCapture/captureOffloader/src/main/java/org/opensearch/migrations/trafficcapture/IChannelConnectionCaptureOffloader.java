package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;

public interface IChannelConnectionCaptureOffloader {

    default String getRequestMethod() {
        throw new UnsupportedOperationException("Underlying object does not support this Metadata function");
    }

}
