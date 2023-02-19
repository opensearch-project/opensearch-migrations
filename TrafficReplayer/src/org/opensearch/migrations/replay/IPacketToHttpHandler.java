package org.opensearch.migrations.replay;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
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

    void consumeBytes(byte[] nextRequestPacket) throws InvalidHttpStateException;
    void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback) throws InvalidHttpStateException;
}
