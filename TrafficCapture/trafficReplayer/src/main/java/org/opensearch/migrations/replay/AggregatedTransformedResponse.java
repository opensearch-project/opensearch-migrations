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
                                         ArrayList<byte[]> requestPackets,
                                         ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
                                         HttpRequestTransformationStatus transformationStatus) {
        super(responseSizeInBytes, responseDuration, requestPackets, responsePackets);
        this.transformationStatus = transformationStatus;
        errorCause = null;
    }

    public AggregatedTransformedResponse(AggregatedRawResponse o, Throwable exception) {
        super(o.responseSizeInBytes, o.responseDuration, o.requestPackets, o.responsePackets);
        transformationStatus = HttpRequestTransformationStatus.ERROR;
        errorCause = exception;
    }

    public AggregatedTransformedResponse(AggregatedRawResponse o, HttpRequestTransformationStatus status) {
        this(o.responseSizeInBytes, o.responseDuration, o.requestPackets, o.responsePackets, status);
    }

    @Override
    protected void addSubclassInfoForToString(StringBuilder sb) {
        sb.append(", transformStatus=").append(getTransformationStatus());
        if (getErrorCause() != null) {
            sb.append(", errorCause=").append(getErrorCause().toString());
        }
    }
}
