package org.opensearch.migrations.replay;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Optional;

public class AggregatedTransformedResponse extends AggregatedRawResponse {

    public enum HttpRequestTransformationStatus {
        SKIPPED, COMPLETED, ERROR
    }

    @Getter
    private final Throwable errorCause;

    @Getter
    @Setter
    private HttpRequestTransformationStatus transformationStatus;

    public AggregatedTransformedResponse(int responseSizeInBytes,
                                         Duration responseDuration,
                                         ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> packets,
                                         HttpRequestTransformationStatus transformationStatus) {
        super(responseSizeInBytes, responseDuration, packets);
        this.transformationStatus = transformationStatus;
        errorCause = null;
    }

    public AggregatedTransformedResponse(AggregatedRawResponse o, Throwable exception) {
        super(o.responseSizeInBytes, o.responseDuration, o.responsePackets);
        transformationStatus = HttpRequestTransformationStatus.ERROR;
        errorCause = exception;
    }

    public AggregatedTransformedResponse(AggregatedRawResponse o, HttpRequestTransformationStatus status) {
        this(o.responseSizeInBytes, o.responseDuration, o.responsePackets, status);
    }
}
