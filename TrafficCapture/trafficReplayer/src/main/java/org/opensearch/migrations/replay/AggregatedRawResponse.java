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

    public AggregatedRawResponse(
        HttpResponse rawResponse,
        int responseSizeInBytes,
        Duration responseDuration,
        List<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        Throwable error
    ) {
        super(responseSizeInBytes, responseDuration, responsePackets, error);
        this.rawResponse = rawResponse;
    }


    public static class Builder extends AggregatedRawResult.Builder<Builder> {
        protected HttpResponse rawResponse;

        public Builder(Instant requestSendTime) {
            super(requestSendTime);
        }

        public AggregatedRawResponse build() {
            return new AggregatedRawResponse(
                rawResponse,
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
    }

    public static Builder builder(Instant i) {
        return new Builder(i);
    }
}
