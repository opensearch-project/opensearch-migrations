package org.opensearch.migrations.trafficcapture.kafkaoffloader;

/**
 * Indicates that process-local capture ownership or lifecycle state violated an invariant.
 * Continuing in pass-through mode is unsafe because the process can no longer trust its own
 * ordering or registry bookkeeping.
 */
final class CorruptedCaptureStateException extends IllegalStateException {
    CorruptedCaptureStateException(String message) {
        super(message);
    }
}
