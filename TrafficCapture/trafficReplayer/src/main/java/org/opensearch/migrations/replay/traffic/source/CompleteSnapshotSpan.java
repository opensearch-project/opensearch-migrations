package org.opensearch.migrations.replay.traffic.source;

import lombok.NonNull;

public record CompleteSnapshotSpan(
    long sequence,
    long firstOffset,
    long lastOffset,
    @NonNull String routingPlanId
) {
    public CompleteSnapshotSpan {
        if (firstOffset > lastOffset) {
            throw new IllegalArgumentException("Snapshot first offset must not exceed its last offset");
        }
    }
}
