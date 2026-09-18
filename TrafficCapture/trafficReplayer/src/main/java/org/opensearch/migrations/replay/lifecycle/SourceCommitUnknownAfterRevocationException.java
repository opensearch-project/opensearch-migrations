package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.Getter;
import lombok.NonNull;

/**
 * A commit was accepted by the source owner, but partition revocation occurred before this process
 * received a definitive broker result.
 */
@SuppressWarnings("java:S110") // The typed cause remains a cancellation signal for async propagation.
public final class SourceCommitUnknownAfterRevocationException extends CancellationException {
    @Getter
    private final SourcePartitionKey partition;

    public SourceCommitUnknownAfterRevocationException(@NonNull SourcePartitionKey partition) {
        super("source commit result became unknown after revocation for " + partition);
        this.partition = partition;
    }
}
