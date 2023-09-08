package org.opensearch.migrations.replay;


import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Stream;

@Slf4j
public class AggregatedRawResponse implements Serializable {

    protected final int responseSizeInBytes;
    @Getter
    protected final Duration responseDuration;
    protected final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets;
    @Getter
    protected final Throwable error;

    public static Builder builder(Instant i) {
        return new Builder(i);
    }

    public byte[][] getCopyOfPackets() {
            return responsePackets.stream()
                    .map(kvp->kvp.getValue())
                    .map(x->Arrays.copyOf(x,x.length))
                    .toArray(byte[][]::new);
    }

    public static class Builder {
        private final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> receiptTimeAndResponsePackets;
        private final Instant requestSendTime;
        protected Throwable error;

        public Builder(Instant requestSendTime) {
            receiptTimeAndResponsePackets = new ArrayList<>();
            this.requestSendTime = requestSendTime;
        }

        public AggregatedRawResponse build() {
            var totalBytes = receiptTimeAndResponsePackets.stream().mapToInt(kvp->kvp.getValue().length).sum();
            return new AggregatedRawResponse(totalBytes, Duration.between(requestSendTime, Instant.now()),
                    receiptTimeAndResponsePackets, error);
        }

        public AggregatedRawResponse.Builder addResponsePacket(byte[] packet) {
            return addResponsePacket(packet, Instant.now());
        }

        public AggregatedRawResponse.Builder addErrorCause(Throwable t) {
            error = t;
            return this;
        }

        public AggregatedRawResponse.Builder addResponsePacket(byte[] packet, Instant timestamp) {
            receiptTimeAndResponsePackets.add(new AbstractMap.SimpleEntry<>(timestamp, packet));
            return this;
        }
    }

    public AggregatedRawResponse(int responseSizeInBytes, Duration responseDuration,
                                 ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
                                 Throwable error) {
        this.responseSizeInBytes = responseSizeInBytes;
        this.responseDuration = responseDuration;
        this.responsePackets = responsePackets;
        this.error = error;
    }

    int getResponseSizeInBytes() {
        return this.responseSizeInBytes;
    }
    Duration getResponseDuration() {
        return this.responseDuration;
    }
    Stream<AbstractMap.SimpleEntry<Instant, byte[]>> getReceiptTimeAndResponsePackets() {
        return this.responsePackets.stream();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IResponseSummary{");
        sb.append("responseSizeInBytes=").append(responseSizeInBytes);
        sb.append(", responseDuration=").append(responseDuration);
        sb.append(", # of responsePackets=").append(""+
                (this.responsePackets==null ? "-1" : "" + this.responsePackets.size()));
        addSubclassInfoForToString(sb);
        sb.append('}');
        return sb.toString();
    }

    protected void addSubclassInfoForToString(StringBuilder sb) {}
}
