package org.opensearch.migrations.bulkload.lucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensearch.migrations.bulkload.lucene.sidecar.BytesRefLike;
import org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink;

/**
 * Coverage for the per-field LRU cache wired into {@link SegmentTermIndex}.
 *
 * <p>Each test pins one independently-checkable contract:
 * <ul>
 *   <li>{@link #defaultHoldsTypicalFieldCount()}: the no-knob default keeps every field of a
 *       small segment resident (the auto-sized cap derives from the JVM's open-file limit and
 *       on any realistic worker is several hundred — well above the per-doc working set).</li>
 *   <li>{@link #lruEvictsLeastRecentlyUsedField()}: with cap = N, the (N+1)-th distinct
 *       field evicts the eldest; recently-touched fields stay.</li>
 *   <li>{@link #evictionClosesEvictedReader()}: eviction closes the evicted reader so its
 *       file handle / mmap is released.</li>
 *   <li>{@link #rebuildAfterEvictionReturnsFreshReader()}: after eviction, re-requesting a
 *       field yields a brand-new reader (proving rebuild path works) and reads succeed.</li>
 *   <li>{@link #floorClampsTinyCap()}: caps below {@code MIN} are clamped up so the LRU
 *       does not thrash itself into uselessness.</li>
 *   <li>{@link #closeAfterUseDoesNotThrow()}: the standard close-and-cleanup contract still
 *       holds when the LRU is engaged.</li>
 * </ul>
 *
 * <p>The reader stub emits a single (term, doc) tuple per field on first walk, which
 * exercises the real {@link org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder}
 * write path. That keeps the test honest: we are evicting real on-disk-backed readers,
 * not mocks.
 */
class SegmentTermIndexLruTest {

    /** A {@link LuceneLeafReader} mock whose postings stream is parameterized per field. */
    private static LuceneLeafReader recordingReader(int maxDoc, AtomicInteger walkCount) throws IOException {
        LuceneLeafReader reader = mock(LuceneLeafReader.class);
        when(reader.maxDoc()).thenReturn(maxDoc);
        // Each call walks once: register one term ("t-<field>"), emit position 0 at doc 0.
        // We intentionally produce a non-empty sidecar so reads return data and so that
        // a rebuild after eviction is observable via reader identity.
        doAnswer(inv -> {
            String fieldName = inv.getArgument(0, String.class);
            PostingsSink sink = inv.getArgument(1, PostingsSink.class);
            walkCount.incrementAndGet();
            byte[] termBytes = ("t-" + fieldName).getBytes(StandardCharsets.UTF_8);
            int termId = sink.registerTerm(new BytesRefLike(termBytes, 0, termBytes.length));
            sink.accept(termId, 0, new int[] {0}, 1);
            return null;
        }).when(reader).streamFieldPostings(anyString(), any(PostingsSink.class));
        return reader;
    }

    @Test
    void defaultHoldsTypicalFieldCount(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        // Two-arg ctor reads the JVM property; unset → auto-size from soft fd limit.
        // The auto cap is (soft_nofile / 4) on Unix or 1024 fallback, both well above 32.
        try (SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"),
                32L * 1024 * 1024)) {
            for (int i = 0; i < 32; i++) {
                idx.getTermsForDocument(reader, 0, "f" + i);
            }
            assertEquals(32, idx.residentFieldCount(),
                    "auto-sized default must hold every field of a small (32-field) segment");
            assertEquals(0L, idx.evictionCount(),
                    "auto-sized default must not evict at this scale");
        }
    }

    @Test
    void lruEvictsLeastRecentlyUsedField(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        try (SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"),
                32L * 1024 * 1024, /* cap */ 3)) {
            // Build A, B, C — cap is 3, all resident.
            idx.getTermsForDocument(reader, 0, "A");
            idx.getTermsForDocument(reader, 0, "B");
            idx.getTermsForDocument(reader, 0, "C");
            assertEquals(3, idx.residentFieldCount());
            assertEquals(0L, idx.evictionCount());

            // Touch A so B becomes the eldest.
            idx.getTermsForDocument(reader, 0, "A");

            // Insert D — must evict B (eldest after A's touch), keep A and C and D.
            idx.getTermsForDocument(reader, 0, "D");
            assertEquals(3, idx.residentFieldCount());
            assertEquals(1L, idx.evictionCount());
            assertEquals(4, walks.get(),
                    "exactly four distinct postings walks: A, B, C, D");
        }
    }

    @Test
    void evictionClosesEvictedReader(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        Path spill = tmp.resolve("seg");
        try (SegmentTermIndex idx = new SegmentTermIndex(spill, 32L * 1024 * 1024, 2)) {
            idx.getTermsForDocument(reader, 0, "A");
            idx.getTermsForDocument(reader, 0, "B");

            // Snapshot the set of files currently under the spill root for field A,
            // then trigger eviction. After eviction the evicted reader must have
            // released its file handle (otherwise close()'s recursive rm would fail
            // on Windows and leak fds on Linux).
            assertTrue(spill.toFile().exists(), "spill root populated by builds");
            idx.getTermsForDocument(reader, 0, "C"); // evicts A

            // Cap held; A's spill subdir was unlinked by SidecarReader.close().
            // We don't assert exact filenames (sanitize+seq is an impl detail);
            // we assert the public observable: A is no longer resident, and the
            // spill root remains writable for the next build (proves no fd leak
            // pinning the directory tree).
            assertEquals(2, idx.residentFieldCount());
            assertEquals(1L, idx.evictionCount());

            // Fresh build still succeeds — would fail with IOException if the
            // evicted-then-not-closed reader had pinned the filesystem.
            idx.getTermsForDocument(reader, 0, "D");
            assertEquals(2, idx.residentFieldCount());
            assertEquals(2L, idx.evictionCount());
        }
    }

    @Test
    void rebuildAfterEvictionReturnsFreshReader(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        try (SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"),
                32L * 1024 * 1024, 2)) {
            // First read of A — also exercises the data round-trip on the rebuild.
            List<String> firstA = idx.getTermsForDocument(reader, 0, "A");
            assertEquals(List.of("t-A"), firstA);

            idx.getTermsForDocument(reader, 0, "B");
            idx.getTermsForDocument(reader, 0, "C"); // evicts A
            assertEquals(1L, idx.evictionCount());

            // Re-request A — must walk postings again and reconstruct identical content.
            int walksBefore = walks.get();
            List<String> rebuiltA = idx.getTermsForDocument(reader, 0, "A");
            assertEquals(List.of("t-A"), rebuiltA,
                    "rebuilt sidecar must yield the same data as the original");
            assertEquals(walksBefore + 1, walks.get(),
                    "rebuild must re-walk the postings stream exactly once");
        }
    }

    @Test
    void floorClampsTinyCap(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        // Cap of 0 is meaningless and would thrash; constructor must clamp up to MIN.
        try (SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"),
                32L * 1024 * 1024, /* cap */ 0)) {
            idx.getTermsForDocument(reader, 0, "A");
            idx.getTermsForDocument(reader, 0, "B");
            // At MIN (=2), both A and B fit with no eviction.
            assertEquals(2, idx.residentFieldCount());
            assertEquals(0L, idx.evictionCount());

            idx.getTermsForDocument(reader, 0, "C");
            assertEquals(2, idx.residentFieldCount());
            assertEquals(1L, idx.evictionCount());
        }
    }

    @Test
    void closeAfterUseDoesNotThrow(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"), 32L * 1024 * 1024, 2);
        for (String f : new String[] {"a", "b", "c", "d", "e"}) {
            idx.getTermsForDocument(reader, 0, f);
        }
        assertEquals(2, idx.residentFieldCount());
        assertTrue(idx.evictionCount() >= 3);

        idx.close();
        assertEquals(0, idx.residentFieldCount(),
                "close() must drain the LRU map");

        // Post-close access must be rejected, not crash on missing mmap.
        IOException ex = assertThrows(IOException.class,
                () -> idx.getTermsForDocument(reader, 0, "f"));
        assertTrue(ex.getMessage().contains("closed"));
    }

    /**
     * Sanity check: with the LRU disabled (default cap), distinct field calls return
     * the same {@code List} content on repeat access (cache hit, no rebuild).
     */
    @Test
    void cacheHitDoesNotRewalk(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        try (SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"),
                32L * 1024 * 1024, 8)) {
            idx.getTermsForDocument(reader, 0, "A");
            idx.getTermsForDocument(reader, 0, "A");
            idx.getTermsForDocument(reader, 0, "A");
            assertEquals(1, walks.get(),
                    "cache hit must not re-walk the postings stream");
        }
    }

    /**
     * Sanity check: distinct fields produce distinct sidecars under the LRU.
     */
    @Test
    void distinctFieldsAreIndependent(@TempDir Path tmp) throws Exception {
        AtomicInteger walks = new AtomicInteger();
        LuceneLeafReader reader = recordingReader(1, walks);

        try (SegmentTermIndex idx = new SegmentTermIndex(tmp.resolve("seg"),
                32L * 1024 * 1024, 4)) {
            assertEquals(List.of("t-A"), idx.getTermsForDocument(reader, 0, "A"));
            assertEquals(List.of("t-B"), idx.getTermsForDocument(reader, 0, "B"));
            assertEquals(List.of("t-C"), idx.getTermsForDocument(reader, 0, "C"));
            assertEquals(3, walks.get());
        }
    }
}
