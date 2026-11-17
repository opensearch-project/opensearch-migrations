package org.opensearch.migrations.trafficcapture.kafkaoffloader;

/**
 * Indicates that process-local capture ownership or lifecycle state violated an invariant, such
 * as duplicate connection ownership or an impossible writer transition. Unlike an ordinary Kafka
 * failure, this means the process can no longer trust its own ordering and registry bookkeeping.
 *
 * <p>Continuing in pass-through mode would hide an unknown capture gap, so this condition belongs
 * to the unstable-process termination path rather than the configurable capture-failure policy.
 */
final class CorruptedCaptureStateException extends IllegalStateException {
    CorruptedCaptureStateException(String message) {
        super(message);
    }
}
