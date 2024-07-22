package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ByteBufList;

import lombok.Getter;
import lombok.Setter;

public class TransformedTargetRequestAndResponse extends AggregatedRawResponse {

    protected final ByteBufList requestPackets;

    @Getter
    @Setter
    private HttpRequestTransformationStatus transformationStatus;

    public TransformedTargetRequestAndResponse(
        ByteBufList requestPackets,
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
        ByteBufList requestPackets,
        HttpRequestTransformationStatus transformationStatus,
        Throwable exception
    ) {
        super(0, Duration.ZERO, null, exception);
        this.requestPackets = requestPackets;
        this.transformationStatus = transformationStatus;
    }

    public TransformedTargetRequestAndResponse(
        ByteBufList requestPackets,
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
