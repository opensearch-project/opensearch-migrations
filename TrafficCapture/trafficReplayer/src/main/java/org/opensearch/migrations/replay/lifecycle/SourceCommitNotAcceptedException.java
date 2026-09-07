package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.Getter;
import lombok.NonNull;

/**
 * The source generation was already unavailable when commit submission was attempted.
 *
 * <p>This is distinct from {@link SourceRunwayLostException}: no source commit was accepted, so the
 * record remains eligible for an explicit retain disposition.
 */
@SuppressWarnings("java:S110") // The typed cause must remain a CancellationException for lifecycle propagation.
public final class SourceCommitNotAcceptedException extends CancellationException {
    @Getter
    private final SourcePartitionKey partition;

    public SourceCommitNotAcceptedException(@NonNull SourcePartitionKey partition) {
        super("source commit was not accepted for " + partition);
        this.partition = partition;
    }
}
