package org.opensearch.migrations.bulkload.common;

/**
 * Shared helpers for surfacing non-retriable snapshot read failures consistently across the
 * migration entrypoints (document migration and metadata migration).
 *
 * @see SnapshotReadFailure
 */
public final class SnapshotReadFailures {

    private SnapshotReadFailures() {
    }

    /**
     * Exit code emitted when a non-retriable snapshot read failure terminates a process. Shared by
     * the document-migration worker and the metadata-migration command so a workflow can detect a
     * snapshot read failure by a single code regardless of which step hit it.
     */
    public static final int EXIT_CODE = 5;

    /** Bound the cause-chain walk so a pathological cyclic chain can never loop forever. */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 100;

    /**
     * Walk the cause chain of {@code t} and return the first throwable marked as a
     * {@link SnapshotReadFailure}, or {@code null} if none is present. The match may be the
     * top-level throwable or any wrapped cause, since snapshot read failures often surface wrapped
     * (e.g. an {@code RfsException} re-thrown around the reactive pipeline, or a metadata wrapper).
     */
    public static Throwable find(Throwable t) {
        var current = t;
        for (int depth = 0; current != null && depth < MAX_CAUSE_CHAIN_DEPTH; depth++) {
            if (current instanceof SnapshotReadFailure) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}