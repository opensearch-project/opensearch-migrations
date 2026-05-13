package org.opensearch.migrations.bulkload.lucene;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

/**
 * Tunables for {@link SegmentTermIndex} on-disk spill files.
 *
 * <p>Spill data is co-located with the unpacked Lucene segment under
 * {@code <indexDirectoryPath>/.rfs-spill/seg-<seq>/}. That directory is the same one
 * {@link org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker} chose for
 * the shard's Lucene files (rooted at the {@code --lucene-dir} CLI arg), so cleanup
 * happens naturally when the worker wipes its Lucene scratch tree on shutdown or via
 * {@code --clear-existing}. No separate {@code /tmp/rfs-spill-<pid>} root is needed.
 *
 * <p>Sort-buffer size is exposed as a single JVM {@code -D} flag so operators can tune
 * it on the worker container without a config plumbing change.
 *
 * <dl>
 *   <dt>{@code rfs.reconstruction.sortBufferBytes}</dt>
 *   <dd>In-memory sort buffer budget per field build. Default: 256 MiB.
 *       Smaller buffer = more runs = more merge passes = more disk I/O, but bounded heap.
 *       Tune down via {@code -Drfs.reconstruction.sortBufferBytes} on smaller workers.</dd>
 *
 *   <dt>{@code rfs.reconstruction.maxOpenFieldSidecars}</dt>
 *   <dd>Maximum number of per-field {@link org.opensearch.migrations.bulkload.lucene.sidecar.SidecarReader}
 *       instances kept open in {@link SegmentTermIndex#byField} at one time. Sidecar readers hold
 *       an mmap of the per-field doc index plus an open file handle on the encoded sidecar; on
 *       segments with hundreds of analyzed-text fields the cumulative file-descriptor and
 *       virtual-address-space pressure can exhaust worker limits before any one field is large
 *       enough to trip the spill-byte budget. When this cap is set, the cache uses an
 *       access-order LRU and closes (and unlinks) evicted sidecars; if a previously evicted
 *       field is re-requested it is rebuilt from postings. Default: {@link Integer#MAX_VALUE}
 *       (unbounded, opt-in for safety — the existing default behavior).</dd>
 * </dl>
 */
@Slf4j
public final class SourcelessSpillConfig {

    public static final String SORT_BUFFER_BYTES_PROP = "rfs.reconstruction.sortBufferBytes";
    public static final String MAX_OPEN_FIELD_SIDECARS_PROP = "rfs.reconstruction.maxOpenFieldSidecars";

    /** 256 MiB sort buffer — tuned for production 4+ GiB heaps. */
    public static final long DEFAULT_SORT_BUFFER_BYTES = 256L * 1024 * 1024;

    /** Unbounded by default — opt-in to LRU eviction by setting the {@code -D} flag. */
    public static final int DEFAULT_MAX_OPEN_FIELD_SIDECARS = Integer.MAX_VALUE;

    /** Floor on the LRU cap — below 2 the LRU thrashes and never holds the working set. */
    public static final int MIN_MAX_OPEN_FIELD_SIDECARS = 2;

    /** Sub-directory of the shard's Lucene dir where per-segment spill trees live. */
    static final String SPILL_SUBDIR = ".rfs-spill";

    private static final AtomicLong SEGMENT_SEQ = new AtomicLong();

    private SourcelessSpillConfig() {}

    /**
     * Allocates a unique per-segment spill subdirectory inside the shard's Lucene
     * directory: {@code <indexDirectoryPath>/.rfs-spill/seg-<seq>}. The leaf is not
     * created here; {@link org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder}
     * creates it on first write, and {@link SegmentTermIndex#close()} deletes it.
     */
    public static Path newSegmentSpillRoot(Path indexDirectoryPath) {
        long seq = SEGMENT_SEQ.incrementAndGet();
        return indexDirectoryPath.resolve(SPILL_SUBDIR).resolve("seg-" + seq);
    }

    /**
     * Returns the configured sort-buffer budget for external merge sort, or the
     * default if unset. Values below one tuple width are clamped up so builds don't deadlock.
     */
    public static long sortBufferBytes() {
        String override = System.getProperty(SORT_BUFFER_BYTES_PROP);
        if (override == null || override.isBlank()) return DEFAULT_SORT_BUFFER_BYTES;
        try {
            long n = Long.parseLong(override.trim());
            return Math.max(org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder.RECORD_BYTES, n);
        } catch (NumberFormatException e) {
            log.warn("Invalid {}={}, using default {}", SORT_BUFFER_BYTES_PROP, override, DEFAULT_SORT_BUFFER_BYTES);
            return DEFAULT_SORT_BUFFER_BYTES;
        }
    }

    /**
     * Returns the configured cap on simultaneously-open per-field sidecar readers, or
     * {@link #DEFAULT_MAX_OPEN_FIELD_SIDECARS} if unset. Values below
     * {@link #MIN_MAX_OPEN_FIELD_SIDECARS} are clamped up so the LRU does not thrash.
     */
    public static int maxOpenFieldSidecars() {
        String override = System.getProperty(MAX_OPEN_FIELD_SIDECARS_PROP);
        if (override == null || override.isBlank()) return DEFAULT_MAX_OPEN_FIELD_SIDECARS;
        try {
            int n = Integer.parseInt(override.trim());
            return Math.max(MIN_MAX_OPEN_FIELD_SIDECARS, n);
        } catch (NumberFormatException e) {
            log.warn(
                    "Invalid {}={}, using default {}",
                    MAX_OPEN_FIELD_SIDECARS_PROP,
                    override,
                    DEFAULT_MAX_OPEN_FIELD_SIDECARS);
            return DEFAULT_MAX_OPEN_FIELD_SIDECARS;
        }
    }
}
