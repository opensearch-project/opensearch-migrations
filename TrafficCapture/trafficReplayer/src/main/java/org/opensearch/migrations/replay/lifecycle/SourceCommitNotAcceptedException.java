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
 * The source generation was already unavailable when commit submission was attempted.
 *
 * <p>This is distinct from {@link SourceRunwayLostException}: no source commit was accepted, so the
 * record remains eligible for an explicit retain disposition.
 */
// REBUILD-LIMBO-START(G11)
/*
@SuppressWarnings("java:S110") // The typed cause must remain a CancellationException for lifecycle propagation.
public final class SourceCommitNotAcceptedException extends CancellationException {
    @Getter
    private final SourcePartitionKey partition;

    public SourceCommitNotAcceptedException(@NonNull SourcePartitionKey partition) {
        super("source commit was not accepted for " + partition);
        this.partition = partition;
    }
}

*/
// REBUILD-LIMBO-END(G11)