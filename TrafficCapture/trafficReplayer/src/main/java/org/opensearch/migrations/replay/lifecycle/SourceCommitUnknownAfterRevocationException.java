package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.Getter;
import lombok.NonNull;

*/
// REBUILD-LIMBO-END(G11)
/**
 * A commit was accepted by the source owner, but partition revocation occurred before this process
 * received a definitive broker result.
 */
// REBUILD-LIMBO-START(G11)
/*
@SuppressWarnings("java:S110") // The typed cause remains a cancellation signal for async propagation.
public final class SourceCommitUnknownAfterRevocationException extends CancellationException {
    @Getter
    private final SourcePartitionKey partition;

    public SourceCommitUnknownAfterRevocationException(@NonNull SourcePartitionKey partition) {
        super("source commit result became unknown after revocation for " + partition);
        this.partition = partition;
    }
}

*/
// REBUILD-LIMBO-END(G11)