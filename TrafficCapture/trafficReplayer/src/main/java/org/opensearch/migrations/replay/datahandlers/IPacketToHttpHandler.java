package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import java.io.IOException;
import java.util.function.Consumer;

public interface IPacketToHttpHandler {

    static class InvalidHttpStateException extends Exception {
        public InvalidHttpStateException(String message) {
            super(message);
        }

        public InvalidHttpStateException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }
    }

    void consumeBytes(byte[] nextRequestPacket) throws InvalidHttpStateException, IOException;
    void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback) throws InvalidHttpStateException, IOException;
}
