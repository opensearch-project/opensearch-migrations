package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

/**
 * Tunables + per-process root directory management for {@link SegmentTermIndex}
 * on-disk spill files.
 *
 * <p>The two knobs are intentionally tiny in surface area so operators can set them
 * via JVM {@code -D} flags on the worker container without needing a full config
 * plumbing change. If either is unset the defaults used here are the ones documented
 * in {@code docs/plans/2026-05-04-sourceless-offline-term-index.md}.
 *
 * <dl>
 *   <dt>{@code rfs.reconstruction.spillRoot}</dt>
 *   <dd>Directory under which per-process, per-segment spill trees are created.
 *       Default: {@code ${java.io.tmpdir}/rfs-spill-<pid>}.
 *       The directory is created lazily on first segment build; the per-process
 *       root is shared across all segments handled by this JVM. Deletion is
 *       per-segment (via {@link SegmentTermIndex#close()}), not per-process —
 *       the worker's filesystem cleanup on container exit handles the root.</dd>
 *
 *   <dt>{@code rfs.reconstruction.sortBufferBytes}</dt>
 *   <dd>In-memory sort buffer budget per field build. Default: 256 MiB.
 *       Affects the number of spill-runs the external merge sort produces
 *       (smaller buffer = more runs = more merge passes = more disk I/O,
 *       but bounded heap). The 256 MiB default targets production workers
 *       with 4+ GiB heaps; tune down via {@code -Drfs.reconstruction.sortBufferBytes}
 *       on smaller workers if needed.</dd>
 * </dl>
 *
 * <p>This class is intentionally stateless apart from the segment-sequence counter,
 * which is monotonic per JVM and guarantees a unique spill subdirectory per
 * {@link LuceneReader#readDocsFromSegment} invocation even when two flux'es
 * happen to share the same underlying segment name.
 */
@Slf4j
public final class SourcelessSpillConfig {

    public static final String SPILL_ROOT_PROP = "rfs.reconstruction.spillRoot";
    public static final String SORT_BUFFER_BYTES_PROP = "rfs.reconstruction.sortBufferBytes";

    /** 256 MiB sort buffer — tuned for production 4+ GiB heaps. */
    public static final long DEFAULT_SORT_BUFFER_BYTES = 256L * 1024 * 1024;

    private static final AtomicLong SEGMENT_SEQ = new AtomicLong();
    private static final AtomicReference<Path> PROCESS_ROOT = new AtomicReference<>();

    private SourcelessSpillConfig() {}

    /**
     * Returns the per-process spill root, creating it on first call. Callers
     * should not delete this directory — {@link SegmentTermIndex#close()}
     * cleans up only the per-segment subdirectory it owns.
     */
    public static synchronized Path processRoot() {
        Path existing = PROCESS_ROOT.get();
        if (existing == null) {
            String override = System.getProperty(SPILL_ROOT_PROP);
            Path root;
            if (override != null && !override.isBlank()) {
                root = Paths.get(override);
            } else {
                String tmp = System.getProperty("java.io.tmpdir", "/tmp");
                String pid = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
                root = Paths.get(tmp, "rfs-spill-" + pid);
            }
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create RFS spill root " + root, e);
            }
            log.info("RFS sourceless reconstruction spill root: {}", root);
            PROCESS_ROOT.set(root);
            return root;
        }
        return existing;
    }

    /**
     * Allocates a unique per-segment spill subdirectory under {@link #processRoot()}.
     * The directory name is {@code seg-<seq>} where seq is a per-JVM monotonic counter.
     * The subdirectory is not created here; {@link org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder}
     * creates it on first write.
     */
    public static Path newSegmentSpillRoot() {
        long seq = SEGMENT_SEQ.incrementAndGet();
        return processRoot().resolve("seg-" + seq);
    }

    /**
     * Returns the configured sort-buffer budget for external merge sort, or the
     * default if unset. Values below the 12-byte tuple width clamp up to one tuple
     * so builds don't deadlock.
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
}
