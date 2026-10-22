package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;

import io.netty.handler.codec.http.HttpResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class AggregatedRawResponse extends AggregatedRawResult {

    protected final HttpResponse rawResponse;
    /** When the last request byte was written to the target, or null if the request was never fully sent. */
    protected final Instant requestSentTime;
    /**
     * Receipt time of the packet that began the final (non-interim) response, or null if none was received.
     */
    protected final Instant finalResponseFirstPacketTime;

    public AggregatedRawResponse(
        HttpResponse rawResponse,
        int responseSizeInBytes,
        Duration responseDuration,
        List<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        Throwable error
    ) {
        this(
            rawResponse,
            responseSizeInBytes,
            responseDuration,
            responsePackets,
            error,
            null,
            responsePackets == null || responsePackets.isEmpty() ? null : responsePackets.get(0).getKey()
        );
    }

    public AggregatedRawResponse(
        HttpResponse rawResponse,
        int responseSizeInBytes,
        Duration responseDuration,
        List<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        Throwable error,
        Instant requestSentTime,
        Instant finalResponseFirstPacketTime
    ) {
        super(responseSizeInBytes, responseDuration, responsePackets, error);
        this.rawResponse = rawResponse;
        this.requestSentTime = requestSentTime;
        this.finalResponseFirstPacketTime = finalResponseFirstPacketTime;
    }

    /** Receipt time of the last response packet, or null if no packet was received. */
    public Instant getLastPacketTime() {
        return packets == null || packets.isEmpty() ? null : packets.get(packets.size() - 1).getKey();
    }

    public static class Builder extends AggregatedRawResult.Builder<Builder> {
        protected HttpResponse rawResponse;
        private Instant requestSentTime;
        private boolean interimResponseSeen;
        private int finalResponseHeadPacketIndex = -1;

        public Builder(Instant requestSendTime) {
            super(requestSendTime);
        }

        @Override
        public AggregatedRawResponse build() {
            return new AggregatedRawResponse(
                rawResponse,
                getTotalBytes(),
                Duration.between(startTime, Instant.now()),
                receiptTimeAndResponsePackets,
                error,
                requestSentTime,
                getFinalResponseFirstPacketTime()
            );
        }

        /**
         * Without an interim response, the final response begins with the first packet. After one, it begins in
         * the packet whose bytes completed the final response's head.
         */
        private Instant getFinalResponseFirstPacketTime() {
            if (receiptTimeAndResponsePackets.isEmpty()) {
                return null;
            }
            if (!interimResponseSeen) {
                return receiptTimeAndResponsePackets.get(0).getKey();
            }
            return finalResponseHeadPacketIndex < 0
                ? null
                : receiptTimeAndResponsePackets.get(finalResponseHeadPacketIndex).getKey();
        }

        public Builder addHttpParsedResponseObject(HttpResponse r) {
            this.rawResponse = r;
            if (finalResponseHeadPacketIndex < 0) {
                finalResponseHeadPacketIndex = receiptTimeAndResponsePackets.size() - 1;
            }
            return this;
        }

        public Builder setRequestSentTime(Instant requestSentTime) {
            this.requestSentTime = requestSentTime;
            return this;
        }

        /** Notes that an interim (1xx) response preceded the final response. */
        public Builder markInterimResponse() {
            this.interimResponseSeen = true;
            return this;
        }
    }

    public static Builder builder(Instant i) {
        return new Builder(i);
    }
}
