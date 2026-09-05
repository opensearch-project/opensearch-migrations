package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.Getter;
import lombok.NonNull;

@SuppressWarnings("java:S110") // The typed cause must remain a CancellationException for lifecycle propagation.
public final class SourceRunwayLostException extends CancellationException {
    @Getter
    private final SourcePartitionKey partition;

    public SourceRunwayLostException(@NonNull SourcePartitionKey partition) {
        super("source runway was lost for " + partition);
        this.partition = partition;
    }
}
