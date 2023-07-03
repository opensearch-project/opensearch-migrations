package org.opensearch.migrations.replay;


import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.stream.Stream;

@Slf4j
public class AggregatedRawResponse implements Serializable {

    protected final int responseSizeInBytes;
    protected final Duration responseDuration;
    protected final ArrayList<byte[]> requestPackets;
    protected final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets;

    public static Builder builder(Instant i) {
        return new Builder(i);
    }

    public static class Builder {
        private final ArrayList<byte[]> requestPackets;
        private final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> receiptTimeAndResponsePackets;
        private final Instant requestSendTime;

        public Builder(Instant requestSendTime) {
            this.requestPackets = new ArrayList<>();
            receiptTimeAndResponsePackets = new ArrayList<>();
            this.requestSendTime = requestSendTime;
        }

        public AggregatedRawResponse build() {
            var totalBytes = receiptTimeAndResponsePackets.stream().mapToInt(kvp->kvp.getValue().length).sum();
            return new AggregatedRawResponse(totalBytes, Duration.between(requestSendTime, Instant.now()),
                    requestPackets, receiptTimeAndResponsePackets);
        }

        public AggregatedRawResponse.Builder addRequestPacket(ByteBuf packet) {
            if (requestPackets.size() > 0 && requestPackets.get(requestPackets.size()-1).length == packet.readableBytes()) {
                log.warn("checkme");
            }
            byte[] output = new byte[packet.readableBytes()];
            packet.readBytes(output);
            packet.resetReaderIndex();
            requestPackets.add(output);
            return this;
        }

        public AggregatedRawResponse.Builder addResponsePacket(byte[] packet) {
            return addResponsePacket(packet, Instant.now());
        }

        public AggregatedRawResponse.Builder addResponsePacket(byte[] packet, Instant timestamp) {
            receiptTimeAndResponsePackets.add(new AbstractMap.SimpleEntry<>(timestamp, packet));
            return this;
        }
    }

    public AggregatedRawResponse(int responseSizeInBytes, Duration responseDuration,
                                 ArrayList<byte[]> requestPackets,
                                 ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets) {
        this.responseSizeInBytes = responseSizeInBytes;
        this.requestPackets = requestPackets;
        this.responseDuration = responseDuration;
        this.responsePackets = responsePackets;
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
