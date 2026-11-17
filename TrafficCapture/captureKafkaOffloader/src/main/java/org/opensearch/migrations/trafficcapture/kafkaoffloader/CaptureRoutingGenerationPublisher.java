package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Defines the asynchronous transition from a Kafka partition set to a usable capture routing
 * generation. Initialization creates fresh writer-partition lanes and persists an initial
 * heartbeat on each before exposing the generation's writer identity.
 *
 * <p>Completing a new generation changes routing for future connections only. Superseded lanes
 * remain independently publishable while their existing connections drain, preserving the
 * immutable route and heartbeat history associated with each connection.
 */
public interface CaptureRoutingGenerationPublisher {
    CompletableFuture<String> initializeRoutingGeneration(Collection<Integer> partitions);

    void stopAfterFailure(Throwable failure);
}
