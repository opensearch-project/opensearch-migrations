package org.opensearch.migrations.trafficcapture;

public interface IChannelConnectionCaptureSerializer<T> extends IChannelConnectionCaptureListener<T> {
    /**
     * Verifies that the acknowledgement for a fully captured Critical Mutation Traffic request
     * still permits that request to be forwarded to the source.
     *
     * @throws RuntimeException when required capture is no longer authoritative
     */
    void validateCriticalMutationTrafficAcknowledgement(T acknowledgement);
}
