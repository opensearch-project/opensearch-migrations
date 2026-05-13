package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
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
 * <p>All knobs are exposed as JVM {@code -D} flags so operators can tune
 * them on the worker container without a config plumbing change.
 *
 * <dl>
 *   <dt>{@code rfs.reconstruction.sortBufferBytes}</dt>
 *   <dd>In-memory sort buffer budget per field build. Default: 256 MiB.
 *       Smaller buffer = more runs = more merge passes = more disk I/O, but bounded heap.</dd>
 *
 *   <dt>{@code rfs.reconstruction.maxSpillBytes}</dt>
 *   <dd>Aggregate ceiling on bytes-in-flight in the per-segment spill across all
 *       concurrent {@code SidecarBuilder}s in this JVM. When the cap is breached,
 *       the offending builder throws {@link SpillBudgetExceededException}, which
 *       propagates as an {@code IOException} and aborts the segment fast and
 *       clean instead of letting the worker run the disk to ENOSPC.
 *       Default: {@link Long#MAX_VALUE} (unbounded — opt-in).</dd>
 *
 *   <dt>{@code rfs.reconstruction.spillFreeSpaceMinBytes}</dt>
 *   <dd>If set, builders sample the spill volume's usable space periodically
 *       and abort early when {@code FileStore.getUsableSpace()} drops below
 *       this floor. Catches the case where another process (or another shard's
 *       Lucene tree) is the one filling the disk. Default: {@code 0} (disabled).</dd>
 * </dl>
 */
@Slf4j
public final class SourcelessSpillConfig {

    public static final String SORT_BUFFER_BYTES_PROP = "rfs.reconstruction.sortBufferBytes";
    public static final String MAX_SPILL_BYTES_PROP = "rfs.reconstruction.maxSpillBytes";
    public static final String SPILL_FREE_SPACE_MIN_BYTES_PROP = "rfs.reconstruction.spillFreeSpaceMinBytes";

    /** 256 MiB sort buffer — tuned for production 4+ GiB heaps. */
    public static final long DEFAULT_SORT_BUFFER_BYTES = 256L * 1024 * 1024;

    /** Unbounded by default; the cap is opt-in. Operators set it via {@code -D}. */
    public static final long DEFAULT_MAX_SPILL_BYTES = Long.MAX_VALUE;

    /** Disabled by default. */
    public static final long DEFAULT_SPILL_FREE_SPACE_MIN_BYTES = 0L;

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
        return parseLongProp(SORT_BUFFER_BYTES_PROP, DEFAULT_SORT_BUFFER_BYTES,
            n -> Math.max(org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder.RECORD_BYTES, n));
    }

    /** Aggregate spill-bytes ceiling across all in-flight builders in this JVM. */
    public static long maxSpillBytes() {
        return parseLongProp(MAX_SPILL_BYTES_PROP, DEFAULT_MAX_SPILL_BYTES, n -> Math.max(0L, n));
    }

    /** Minimum usable bytes that must remain on the spill volume mid-build. */
    public static long spillFreeSpaceMinBytes() {
        return parseLongProp(SPILL_FREE_SPACE_MIN_BYTES_PROP, DEFAULT_SPILL_FREE_SPACE_MIN_BYTES,
            n -> Math.max(0L, n));
    }

    /**
     * Returns the usable space (in bytes) on the file store that contains
     * {@code path}, or {@link Long#MAX_VALUE} if the volume can't be probed
     * (so a probe failure never causes a false positive abort).
     */
    public static long usableSpace(Path path) {
        try {
            FileStore fs = Files.getFileStore(path);
            long n = fs.getUsableSpace();
            return n > 0 ? n : Long.MAX_VALUE;
        } catch (IOException e) {
            log.debug("usableSpace probe failed for {}: {}", path, e.toString());
            return Long.MAX_VALUE;
        }
    }

    private static long parseLongProp(String prop, long defaultValue, java.util.function.LongUnaryOperator clamp) {
        String override = System.getProperty(prop);
        if (override == null || override.isBlank()) return defaultValue;
        try {
            return clamp.applyAsLong(Long.parseLong(override.trim()));
        } catch (NumberFormatException e) {
            log.warn("Invalid {}={}, using default {}", prop, override, defaultValue);
            return defaultValue;
        }
    }
}
