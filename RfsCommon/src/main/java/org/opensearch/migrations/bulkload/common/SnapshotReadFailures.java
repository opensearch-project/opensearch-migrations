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

    /** Bound the cause-chain walk so a pathological cyclic chain can never loop forever. */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 20;

    /**
     * Walk the cause chain of {@code t} and return the <em>deepest</em> throwable marked as a
     * {@link SnapshotReadFailure}, or {@code null} if none is present. The match may be the
     * top-level throwable or any wrapped cause, since snapshot read failures often surface wrapped
     * (e.g. an {@code RfsException} re-thrown around the reactive pipeline, or a metadata wrapper).
     *
     * <p>The deepest marker is preferred so {@link #describe} surfaces the specific inner cause
     * (e.g. {@code CouldNotReadFromS3} with bucket/key) rather than a broad wrapper's generic message.
     */
    public static Throwable find(Throwable t) {
        var current = t;
        Throwable deepestMatch = null;
        for (int depth = 0; current != null && depth < MAX_CAUSE_CHAIN_DEPTH; depth++) {
            if (current instanceof SnapshotReadFailure) {
                deepestMatch = current;
            }
            current = current.getCause();
        }
        return deepestMatch;
    }

    /**
     * Build the single-line, labeled description used to surface a non-retriable snapshot read
     * failure in the logs: the failure reason plus the snapshot path and context. Shared so the
     * document-migration worker and the metadata-migration command emit a consistent message.
     *
     * @param readFailure the throwable returned by {@link #find(Throwable)} (its message is the reason)
     * @param snapshotName the snapshot name, or {@code null} if not set
     * @param repo the repository location (local dir or S3 URI), or {@code null} if not set
     * @param region the S3 region, or {@code null} for non-S3 repositories
     */
    public static String describe(Throwable readFailure, String snapshotName, String repo, String region) {
        return "Non-retriable snapshot read failure: " + readFailure.getMessage()
            + " | snapshot=" + snapshotName + ", repo=" + repo + ", region=" + region;
    }
}
