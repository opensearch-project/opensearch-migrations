package org.opensearch.migrations.trafficcapture.netty.tracing;

import java.util.Locale;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Counts capture-proxy connection lifetimes and attributes closes to a bounded reason.
 *
 * <p>Connection identity is intentionally excluded from metrics and belongs in the corresponding
 * operational log event.</p>
 */
public final class CaptureConnectionMetrics {
    public enum CloseReason {
        NORMAL,
        ASSEMBLY_DURATION,
        HEADER_BYTES,
        TOTAL_BYTES,
        CONNECTION_DURATION,
        CAPTURE_FAILURE
    }

    public static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");
    public static final String CONNECTIONS_ACTIVE = "captureConnectionsActive";
    public static final String CONNECTION_CLOSES = "captureConnectionCloses";

    private final LongUpDownCounter connectionsActive;
    private final LongCounter connectionCloses;

    public CaptureConnectionMetrics(@NonNull Meter meter) {
        connectionsActive = meter.upDownCounterBuilder(CONNECTIONS_ACTIVE)
            .setDescription("Capture-proxy source connections currently open")
            .setUnit("connections")
            .build();
        connectionCloses = meter.counterBuilder(CONNECTION_CLOSES)
            .setDescription("Capture-proxy source connection closes by bounded reason")
            .setUnit("connections")
            .build();
    }

    public void connectionOpened() {
        connectionsActive.add(1);
    }

    public void connectionClosed(@NonNull CloseReason reason) {
        connectionsActive.add(-1);
        connectionCloses.add(
            1,
            Attributes.of(REASON, reason.name().toLowerCase(Locale.ROOT))
        );
    }
}
