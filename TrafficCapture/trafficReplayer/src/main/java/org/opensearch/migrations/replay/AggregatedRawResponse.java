package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.netty.handler.codec.http.HttpResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class AggregatedRawResponse extends AggregatedRawResult {

    protected final HttpResponse rawResponse;
    protected final List<byte[]> interimResponsePackets;

    public AggregatedRawResponse(
        HttpResponse rawResponse,
        int responseSizeInBytes,
        Duration responseDuration,
        List<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        Throwable error
    ) {
        this(rawResponse, List.of(), responseSizeInBytes, responseDuration, responsePackets, error);
    }

    public AggregatedRawResponse(
        HttpResponse rawResponse,
        List<byte[]> interimResponsePackets,
        int responseSizeInBytes,
        Duration responseDuration,
        List<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        Throwable error
    ) {
        super(responseSizeInBytes, responseDuration, responsePackets, error);
        this.rawResponse = rawResponse;
        this.interimResponsePackets = Collections.unmodifiableList(
            interimResponsePackets == null ? List.of() : new ArrayList<>(interimResponsePackets));
    }


    public static class Builder extends AggregatedRawResult.Builder<Builder> {
        protected HttpResponse rawResponse;
        protected final List<byte[]> interimResponsePackets = new ArrayList<>();

        public Builder(Instant requestSendTime) {
            super(requestSendTime);
        }

        @Override
        public AggregatedRawResponse build() {
            return new AggregatedRawResponse(
                rawResponse,
                interimResponsePackets,
                getTotalBytes(),
                Duration.between(startTime, Instant.now()),
                receiptTimeAndResponsePackets,
                error
            );
        }

        public Builder addHttpParsedResponseObject(HttpResponse r) {
            this.rawResponse = r;
            return this;
        }

        public Builder addInterimResponsePacket(byte[] packet) {
            this.interimResponsePackets.add(packet);
            return this;
        }
    }

    public static Builder builder(Instant i) {
        return new Builder(i);
    }
}
