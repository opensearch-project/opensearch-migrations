package org.opensearch.migrations.trafficcapture.netty;

/**
 * Chooses the irreversible process response when required traffic capture can no longer be
 * guaranteed. {@link #FAIL_OPEN} preserves source availability by forwarding without capture and
 * explicitly creates a capture gap; {@link #FAIL_CLOSED} preserves capture completeness by
 * terminating the proxy instead.
 *
 * <p>This policy applies to failures in the capture path, not to corrupted local execution state.
 * An unstable process must terminate regardless of this setting because neither forwarding nor
 * capture can then be trusted.
 */
public enum CaptureFailurePolicy {
    FAIL_OPEN,
    FAIL_CLOSED
}
