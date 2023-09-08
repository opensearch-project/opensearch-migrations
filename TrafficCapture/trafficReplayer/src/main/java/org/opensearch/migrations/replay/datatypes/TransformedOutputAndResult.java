package org.opensearch.migrations.replay.datatypes;

public class TransformedOutputAndResult<T> {

    public final T transformedOutput;

    public final HttpRequestTransformationStatus transformationStatus;
    public final Throwable error;

    public TransformedOutputAndResult(T packetBytes, HttpRequestTransformationStatus status,
                                       Throwable error) {
        this.transformedOutput = packetBytes;
        this.transformationStatus = status;
        this.error = error;
    }
}
