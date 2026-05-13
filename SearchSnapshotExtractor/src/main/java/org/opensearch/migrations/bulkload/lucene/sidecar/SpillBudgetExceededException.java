package org.opensearch.migrations.bulkload.lucene.sidecar;

/**
 * Thrown when a {@link SidecarBuilder} would exceed the JVM-wide aggregate
 * spill cap configured by {@code rfs.reconstruction.maxSpillBytes}, or when
 * the spill volume's usable space drops below
 * {@code rfs.reconstruction.spillFreeSpaceMinBytes}. Distinct from a generic
 * {@link java.io.IOException} so callers (and log scrapers) can match the
 * pre-ENOSPC fail-fast path explicitly.
 */
public class SpillBudgetExceededException extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    public SpillBudgetExceededException(String message) {
        super(message);
    }
}
