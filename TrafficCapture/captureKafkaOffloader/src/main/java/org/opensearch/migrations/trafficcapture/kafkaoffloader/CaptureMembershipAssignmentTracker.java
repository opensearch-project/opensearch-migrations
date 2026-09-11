package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Receives the complete proxy membership decoded from Kafka assignment metadata.
 */
public final class CaptureMembershipAssignmentTracker {
    private final AtomicReference<Set<String>> currentMembers = new AtomicReference<>(Set.of());

    void replaceMembers(Set<String> members) {
        currentMembers.set(Set.copyOf(Objects.requireNonNull(members)));
    }

    public Set<String> currentMembers() {
        return currentMembers.get();
    }

    public boolean satisfiesMinimum(int minimumActiveProxyCount) {
        if (minimumActiveProxyCount <= 0) {
            throw new IllegalArgumentException("minimumActiveProxyCount must be positive");
        }
        return currentMembers.get().size() >= minimumActiveProxyCount;
    }
}
