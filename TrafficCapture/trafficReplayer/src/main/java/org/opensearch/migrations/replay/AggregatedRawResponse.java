package org.opensearch.migrations.replay;


import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Stream;

public class AggregatedRawResponse implements Serializable {

    protected final int responseSizeInBytes;
    protected final Duration responseDuration;
    protected final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets;

    public static Builder builder(Instant i) {
        return new Builder(i);
    }

    public static class Builder {
        private final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> receiptTimeAndResponsePackets;
        private final Instant requestSendTime;

        public Builder(Instant requestSendTime) {
            receiptTimeAndResponsePackets = new ArrayList<>();
            this.requestSendTime = requestSendTime;
        }

        public AggregatedRawResponse build() {
            var totalBytes = receiptTimeAndResponsePackets.stream().mapToInt(kvp->kvp.getValue().length).sum();
            return new AggregatedRawResponse(totalBytes, Duration.between(requestSendTime, Instant.now()),
                    receiptTimeAndResponsePackets);
        }

        public AggregatedRawResponse.Builder addPacket(byte[] packet) {
            return addPacket(packet, Instant.now());
        }

        public AggregatedRawResponse.Builder addPacket(byte[] packet, Instant timestamp) {
            receiptTimeAndResponsePackets.add(new AbstractMap.SimpleEntry<>(timestamp, packet));
            return this;
        }
    }

    public AggregatedRawResponse(int responseSizeInBytes, Duration responseDuration,
                                 ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> packets) {
        this.responseSizeInBytes = responseSizeInBytes;
        this.responseDuration = responseDuration;
        this.responsePackets = packets;
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
        sb.append('}');
        return sb.toString();
    }
}
