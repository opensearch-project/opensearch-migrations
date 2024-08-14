package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.ByteBufList;

@Slf4j
public class AggregatedRawResponse {

    @Getter
    protected final HttpResponse rawResponse;
    @Getter
    protected final int responseSizeInBytes;
    @Getter
    protected final Duration responseDuration;
    protected final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets;
    @Getter
    protected final Throwable error;

    public static Builder builder(Instant i) {
        return new Builder(i);
    }

    public AggregatedRawResponse(
        HttpResponse rawResponse,
        int responseSizeInBytes,
        Duration responseDuration,
        List<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        Throwable error
    ) {
        this.rawResponse = rawResponse;
        this.responseSizeInBytes = responseSizeInBytes;
        this.responseDuration = responseDuration;
        this.responsePackets = responsePackets == null ? null : new ArrayList<>(responsePackets);
        this.error = error;
    }

    public byte[][] getCopyOfPackets() {
        return responsePackets.stream()
            .map(Map.Entry::getValue)
            .map(x -> Arrays.copyOf(x, x.length))
            .toArray(byte[][]::new);
    }

    public ByteBuf getResponseAsByteBuf() {
        return responsePackets == null ? Unpooled.EMPTY_BUFFER :
            ByteBufList.asCompositeByteBufRetained(responsePackets.stream()
                    .map(Map.Entry::getValue).map(Unpooled::wrappedBuffer))
                .asReadOnly();
    }

    public static class Builder {
        private final ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> receiptTimeAndResponsePackets;
        private final Instant requestSendTime;
        protected HttpResponse rawResponse;
        protected Throwable error;

        public Builder(Instant requestSendTime) {
            receiptTimeAndResponsePackets = new ArrayList<>();
            this.requestSendTime = requestSendTime;
            rawResponse = null;
        }

        public AggregatedRawResponse build() {
            var totalBytes = receiptTimeAndResponsePackets.stream().mapToInt(kvp -> kvp.getValue().length).sum();
            return new AggregatedRawResponse(
                rawResponse,
                totalBytes,
                Duration.between(requestSendTime, Instant.now()),
                receiptTimeAndResponsePackets,
                error
            );
        }

        public AggregatedRawResponse.Builder addResponsePacket(byte[] packet) {
            return addResponsePacket(packet, Instant.now());
        }

        public AggregatedRawResponse.Builder addHttpParsedResponseObject(HttpResponse r) {
            this.rawResponse = r;
            return this;
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

    Stream<AbstractMap.SimpleEntry<Instant, byte[]>> getReceiptTimeAndResponsePackets() {
        return Optional.ofNullable(this.responsePackets).stream().flatMap(Collection::stream);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IResponseSummary{");
        sb.append("responseSizeInBytes=").append(responseSizeInBytes);
        sb.append(", responseDuration=").append(responseDuration);
        sb.append(", # of responsePackets=")
            .append((this.responsePackets == null ? "-1" : "" + this.responsePackets.size()));
        sb.append('}');
        return sb.toString();
    }
}
