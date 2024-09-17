package org.opensearch.migrations.replay.datatypes;

public class TransformedOutputAndResult<T> {

    public final T transformedOutput;

    public final HttpRequestTransformationStatus transformationStatus;

    public TransformedOutputAndResult(T packetBytes, HttpRequestTransformationStatus status) {
        this.transformedOutput = packetBytes;
        this.transformationStatus = status;
    }
}
