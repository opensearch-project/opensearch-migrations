package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes the initial manifests that make a proxy assignment usable.
 */
public interface CaptureAssignmentPublisher {
    CompletableFuture<String> installAssignment(Collection<Integer> partitions);

    void failClosed(Throwable failure);
}
