package org.opensearch.migrations.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.DiagnosticPayload;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;

import lombok.Getter;
import lombok.NonNull;

public class TransformedTargetRequestAndResponseList implements AutoCloseable {

    private final AtomicReference<DiagnosticPayload> diagnosticPayload;
    private final AtomicBoolean diagnosticClaimed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Getter
    private final HttpRequestTransformationStatus transformationStatus;

    @Getter
    protected final List<AggregatedRawResponse> responseList;

    public TransformedTargetRequestAndResponseList(
        DiagnosticPayload diagnosticPayload,
        @NonNull HttpRequestTransformationStatus transformationStatus
    ) {
        this.diagnosticPayload = new AtomicReference<>(diagnosticPayload);
        this.transformationStatus = transformationStatus;
        this.responseList = new ArrayList<>();
    }

    public TransformedTargetRequestAndResponseList(
        DiagnosticPayload diagnosticPayload,
        @NonNull HttpRequestTransformationStatus transformationStatus,
        AggregatedRawResponse... aggregatedResponses) {
        this(diagnosticPayload, transformationStatus);
        for (var r : aggregatedResponses) {
            addResponse(r);
        }
    }

    public ByteBufList requestPackets() {
        var payload = diagnosticPayload.get();
        return payload == null ? null : payload.packets();
    }

    public DiagnosticPayload claimDiagnosticPayload() {
        if (!diagnosticClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("diagnostic payload already claimed");
        }
        return diagnosticPayload.getAndSet(null);
    }

    public void addResponse(AggregatedRawResponse r) {
        responseList.add(r);
    }

    public List<AggregatedRawResponse> responses() {
        return Collections.unmodifiableList(responseList);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            var payload = diagnosticPayload.getAndSet(null);
            if (payload != null) {
                payload.close();
            }
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("TransformedTargetRequestAndResponse{");
        sb.append("transformStatus=").append(getTransformationStatus());
        sb.append(responseList.stream()
            .map(AggregatedRawResponse::toString)
            .collect(Collectors.joining("\n", "[", "]")));
        return sb.toString();
    }
}
