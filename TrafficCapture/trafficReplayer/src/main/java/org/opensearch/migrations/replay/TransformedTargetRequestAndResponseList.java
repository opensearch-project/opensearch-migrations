package org.opensearch.migrations.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ByteBufList;

import lombok.Getter;
import lombok.Setter;

public class TransformedTargetRequestAndResponseList {

    protected final ByteBufList requestPackets;

    @Getter
    @Setter
    private HttpRequestTransformationStatus transformationStatus;

    @Getter
    protected final List<AggregatedRawResponse> responseList;

    public TransformedTargetRequestAndResponseList(
        ByteBufList requestPackets,
        HttpRequestTransformationStatus transformationStatus
    ) {
        this.requestPackets = requestPackets;
        this.transformationStatus = transformationStatus;
        this.responseList = new ArrayList<>();
    }

    public TransformedTargetRequestAndResponseList(
        ByteBufList requestPackets,
        HttpRequestTransformationStatus transformationStatus,
        AggregatedRawResponse... aggregatedResponses) {
        this(requestPackets, transformationStatus);
        for (var r : aggregatedResponses) {
            addResponse(r);
        }
    }

    public void addResponse(AggregatedRawResponse r) {
        responseList.add(r);
    }

    public List<AggregatedRawResponse> responses() {
        return Collections.unmodifiableList(responseList);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("TransformedTargetRequestAndResponse{");
        sb.append(", transformStatus=").append(getTransformationStatus());
        sb.append(responseList.stream()
            .map(AggregatedRawResponse::toString)
            .collect(Collectors.joining("\n", "[", "]")));
        return sb.toString();
    }
}
