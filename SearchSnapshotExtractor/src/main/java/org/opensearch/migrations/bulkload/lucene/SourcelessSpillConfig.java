package org.opensearch.migrations.bulkload.lucene;

import java.lang.management.ManagementFactory;
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
 *       enough to trip the spill-byte budget. The cache uses an access-order LRU and closes (and
 *       unlinks) evicted sidecars; if a previously evicted field is re-requested it is rebuilt
 *       from postings.
 *
 *       <p><b>Default: auto-sized from the JVM's open-file soft limit.</b> On Unix the cap is
 *       {@code max(MIN, soft_nofile / 4)} — each resident sidecar reader consumes ~2 file
 *       descriptors, and the {@code / 4} headroom leaves room for the rest of the JVM (sockets,
 *       Lucene segment files, log files, JFR, etc.). On platforms where the soft limit cannot be
 *       discovered, the default is {@code 1024}. To pin an explicit value, set the system
 *       property to a positive integer (clamped up to the {@code MIN} floor); to force the auto
 *       behavior, leave the property unset or set it to {@code auto}.
 *
 *       <p><b>Floor:</b> {@link #MIN_MAX_OPEN_FIELD_SIDECARS}. Below this value the LRU thrashes
 *       on every doc — sourceless reconstruction walks every term-bearing field of every doc in
 *       field order, so a cap below the per-doc working set turns each access into a miss and
 *       triggers a full postings re-walk plus an external sort run. The floor is set high enough
 *       that thrash is bounded for typical segments.</dd>
 * </dl>
 */
@Slf4j
public final class SourcelessSpillConfig {

    public static final String SORT_BUFFER_BYTES_PROP = "rfs.reconstruction.sortBufferBytes";
    public static final String MAX_OPEN_FIELD_SIDECARS_PROP = "rfs.reconstruction.maxOpenFieldSidecars";

    /** 256 MiB sort buffer — tuned for production 4+ GiB heaps. */
    public static final long DEFAULT_SORT_BUFFER_BYTES = 256L * 1024 * 1024;

    /**
     * Floor on the LRU cap. Sourceless reconstruction's access pattern walks every field
     * of every doc in deterministic field order, so any cap below the per-doc working set
     * produces a guaranteed miss on every field access and triggers a full postings re-walk
     * plus an external sort run per access — orders of magnitude slower than the unbounded
     * path the LRU was meant to protect. 32 is a soft lower bound that bounds thrash for
     * typical segments while leaving the knob meaningful for FD-constrained workers.
     */
    public static final int MIN_MAX_OPEN_FIELD_SIDECARS = 32;

    /** Used when the OS soft fd limit cannot be discovered (e.g. Windows). */
    static final int FALLBACK_MAX_OPEN_FIELD_SIDECARS = 1024;

    /** Each resident sidecar reader consumes ~2 fds; divide by 4 to leave JVM headroom. */
    static final int FD_HEADROOM_DIVISOR = 4;

    /** Sub-directory of the shard's Lucene dir where per-segment spill trees live. */
    static final String SPILL_SUBDIR = ".rfs-spill";

    private static final AtomicLong SEGMENT_SEQ = new AtomicLong();

    /** Memoized auto cap so the OS probe runs at most once per JVM. */
    private static volatile int autoCapCache = 0;

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
     * Returns the configured cap on simultaneously-open per-field sidecar readers.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code -Drfs.reconstruction.maxOpenFieldSidecars} is a positive integer, use it
     *       (clamped up to {@link #MIN_MAX_OPEN_FIELD_SIDECARS}; clamp emits a one-time warning).</li>
     *   <li>If unset, blank, or literally {@code "auto"}, return {@link #autoMaxOpenFieldSidecars()}.</li>
     *   <li>If non-numeric, log a warning and fall through to auto.</li>
     * </ol>
     */
    public static int maxOpenFieldSidecars() {
        String override = System.getProperty(MAX_OPEN_FIELD_SIDECARS_PROP);
        if (override == null || override.isBlank() || "auto".equalsIgnoreCase(override.trim())) {
            return autoMaxOpenFieldSidecars();
        }
        try {
            int n = Integer.parseInt(override.trim());
            if (n < MIN_MAX_OPEN_FIELD_SIDECARS) {
                log.warn(
                        "{}={} is below the minimum {} — clamping up. Caps below the per-doc field"
                                + " working set cause LRU thrash on sourceless reconstruction.",
                        MAX_OPEN_FIELD_SIDECARS_PROP,
                        n,
                        MIN_MAX_OPEN_FIELD_SIDECARS);
                return MIN_MAX_OPEN_FIELD_SIDECARS;
            }
            return n;
        } catch (NumberFormatException e) {
            int auto = autoMaxOpenFieldSidecars();
            log.warn(
                    "Invalid {}={}, using auto-sized default {}",
                    MAX_OPEN_FIELD_SIDECARS_PROP,
                    override,
                    auto);
            return auto;
        }
    }

    /**
     * Auto-sizes the LRU cap from the JVM's open-file soft limit (Unix) or a fixed
     * fallback (other platforms). Computed once and memoized for the lifetime of the JVM.
     *
     * <p>The soft fd limit is queried via the {@code com.sun.management.UnixOperatingSystemMXBean}
     * extension, which is present on HotSpot and Corretto builds for Linux and macOS. On Windows
     * (or any JVM where the cast fails or returns a non-positive value) the constant
     * {@link #FALLBACK_MAX_OPEN_FIELD_SIDECARS} is used.
     */
    public static int autoMaxOpenFieldSidecars() {
        int cached = autoCapCache;
        if (cached != 0) return cached;
        int computed;
        try {
            Object osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                long softLimit = unix.getMaxFileDescriptorCount();
                if (softLimit > 0) {
                    long budget = softLimit / FD_HEADROOM_DIVISOR;
                    computed = (int) Math.max(MIN_MAX_OPEN_FIELD_SIDECARS, Math.min(Integer.MAX_VALUE, budget));
                    log.info(
                            "{} auto-sized to {} (soft fd limit {} / {})",
                            MAX_OPEN_FIELD_SIDECARS_PROP,
                            computed,
                            softLimit,
                            FD_HEADROOM_DIVISOR);
                } else {
                    computed = FALLBACK_MAX_OPEN_FIELD_SIDECARS;
                    log.info(
                            "{} auto-size: soft fd limit unavailable (returned {}), using fallback {}",
                            MAX_OPEN_FIELD_SIDECARS_PROP,
                            softLimit,
                            computed);
                }
            } else {
                computed = FALLBACK_MAX_OPEN_FIELD_SIDECARS;
                log.info(
                        "{} auto-size: non-Unix JVM, using fallback {}",
                        MAX_OPEN_FIELD_SIDECARS_PROP,
                        computed);
            }
        } catch (Throwable t) {
            // Defensive: never let an OS-probe failure break reconstruction.
            computed = FALLBACK_MAX_OPEN_FIELD_SIDECARS;
            log.warn(
                    "{} auto-size: probe threw, using fallback {} ({})",
                    MAX_OPEN_FIELD_SIDECARS_PROP,
                    computed,
                    t.toString());
        }
        autoCapCache = computed;
        return computed;
    }
}
