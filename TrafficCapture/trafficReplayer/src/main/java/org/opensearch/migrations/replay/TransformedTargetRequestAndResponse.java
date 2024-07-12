package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;

import lombok.Getter;
import lombok.Setter;

public class TransformedTargetRequestAndResponse extends AggregatedRawResponse {

    protected final TransformedPackets requestPackets;

    @Getter
    @Setter
    private HttpRequestTransformationStatus transformationStatus;

    public TransformedTargetRequestAndResponse(
        TransformedPackets requestPackets,
        int responseSizeInBytes,
        Duration responseDuration,
        ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>> responsePackets,
        HttpRequestTransformationStatus transformationStatus,
        Throwable exception
    ) {
        super(responseSizeInBytes, responseDuration, responsePackets, exception);
        this.requestPackets = requestPackets;
        this.transformationStatus = transformationStatus;
    }

    public TransformedTargetRequestAndResponse(
        TransformedPackets requestPackets,
        HttpRequestTransformationStatus transformationStatus,
        Throwable exception
    ) {
        super(0, Duration.ZERO, null, exception);
        this.requestPackets = requestPackets;
        this.transformationStatus = transformationStatus;
    }

    public TransformedTargetRequestAndResponse(
        TransformedPackets requestPackets,
        AggregatedRawResponse o,
        HttpRequestTransformationStatus status,
        Throwable exception
    ) {
        this(requestPackets, o.responseSizeInBytes, o.responseDuration, o.responsePackets, status, exception);
    }

    @Override
    protected void addSubclassInfoForToString(StringBuilder sb) {
        sb.append(", transformStatus=").append(getTransformationStatus());
        if (getError() != null) {
            sb.append(", errorCause=").append(getError().toString());
        }
    }
}
