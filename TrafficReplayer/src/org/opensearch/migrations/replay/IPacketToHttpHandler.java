package org.opensearch.migrations.replay;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface IPacketToHttpHandler {

    static class InvalidHttpStateException extends Exception {
        public InvalidHttpStateException(String message) {
            super(message);
        }

        public InvalidHttpStateException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }
    }

    static class IResponseSummary implements Serializable {
        protected int responseSizeInBytes;
        protected Duration responseDuration;

        public IResponseSummary(int responseSizeInBytes, Duration responseDuration) {
            this.responseSizeInBytes = responseSizeInBytes;
            this.responseDuration = responseDuration;
        }

        int getResponseSizeInBytes() {
            return this.responseSizeInBytes;
        }
        Duration getResponseDuration() {
            return this.responseDuration;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("IResponseSummary{");
            sb.append("responseSizeInBytes=").append(responseSizeInBytes);
            sb.append(", responseDuration=").append(responseDuration);
            sb.append('}');
            return sb.toString();
        }
    }

    void consumeBytes(byte[] nextRequestPacket) throws InvalidHttpStateException;
    void finalizeRequest(Consumer<IResponseSummary> onResponseFinishedCallback) throws InvalidHttpStateException;
}
