package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.datatypes.ByteBufList;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;

public class AggregatedRawResult {
    @Getter
    protected final int sizeInBytes;
    @Getter
    protected final Duration duration;
    protected final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> packets;
    @Getter
    protected final Throwable error;

    public static class Builder<B extends Builder<B>> {
        protected final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> receiptTimeAndResponsePackets;
        protected final Instant startTime;
        protected Throwable error;

        public Builder(Instant startTime) {
            receiptTimeAndResponsePackets = new ArrayList<>();
            this.startTime = startTime;
        }

        public AggregatedRawResult build() {
            var totalBytes = getTotalBytes();
            return new AggregatedRawResult(
                totalBytes,
                Duration.between(startTime, Instant.now()),
                receiptTimeAndResponsePackets,
                error
            );
        }

        protected int getTotalBytes() {
            return receiptTimeAndResponsePackets.stream().mapToInt(kvp -> kvp.getValue().length).sum();
        }

        public B addErrorCause(Throwable t) {
            error = t;
            return (B) this;
        }

        public B addResponsePacket(byte[] packet) {
            return (B) addResponsePacket(packet, Instant.now());
        }

        public B addResponsePacket(byte[] packet, Instant timestamp) {
            receiptTimeAndResponsePackets.add(new AbstractMap.SimpleEntry<>(timestamp, packet));
            return (B) this;
        }
    }

    public AggregatedRawResult(int sizeInBytes,
                               Duration duration,
                               List<AbstractMap.SimpleEntry<Instant, byte[]>> packets,
                               Throwable error)
    {
        this.sizeInBytes = sizeInBytes;
        this.duration = duration;
        this.packets = packets == null ? null : new ArrayList<>(packets);
        this.error = error;
    }

    public static Builder<?> builder(Instant i) {
        return new Builder<>(i);
    }

    public byte[][] getCopyOfPackets() {
        return packets.stream()
            .map(Map.Entry::getValue)
            .map(x -> Arrays.copyOf(x, x.length))
            .toArray(byte[][]::new);
    }

    public ByteBuf getResponseAsByteBuf() {
        return packets == null ? Unpooled.EMPTY_BUFFER :
            ByteBufList.asCompositeByteBufRetained(packets.stream()
                    .map(Map.Entry::getValue).map(Unpooled::wrappedBuffer))
                .asReadOnly();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IResponseSummary{");
        sb.append("responseSizeInBytes=").append(sizeInBytes);
        sb.append(", responseDuration=").append(duration);
        sb.append(", # of responsePackets=")
            .append((this.packets == null ? "-1" : "" + this.packets.size()));
        sb.append('}');
        return sb.toString();
    }

}
