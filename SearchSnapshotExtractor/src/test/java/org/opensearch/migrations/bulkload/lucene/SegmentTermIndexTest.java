package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link SegmentTermIndex} — the per-segment cache that owns the
 * streaming postings + streaming multi-term cursors and the lazily-built numeric
 * index for trie-encoded fields.
 *
 * <p>Each test wires a tiny in-memory {@code LuceneLeafReader} that returns
 * stub cursors / numeric maps, then verifies:
 * <ul>
 *   <li>Lazy construction: the reader's open/build method is called at most once
 *       per (field, kind), then cached.</li>
 *   <li>Negative caching: a {@code null} cursor (field absent) is cached and
 *       not re-walked on subsequent calls.</li>
 *   <li>Single-term reduction: {@link SegmentTermIndex#getSingleTermForDocument}
 *       reuses the multi-term cursor and returns the first emitted term.</li>
 *   <li>Close lifecycle: every cached cursor sees exactly one {@code close()},
 *       and post-close calls fail loudly with {@link IOException}.</li>
 * </ul>
 */
class SegmentTermIndexTest {

    /** Counts how many times the cursor has been closed. */
    private static final class CountingMultiTermCursor implements StreamingMultiTermPostings {
        final List<List<String>> emissions; // emissions[i] = result for advance(i)
        int closes;
        boolean closed;

        CountingMultiTermCursor(List<List<String>> emissions) {
            this.emissions = emissions;
        }

        @Override
        public List<String> advance(int docId) throws IOException {
            if (closed) throw new IOException("closed");
            if (docId >= emissions.size()) return Collections.emptyList();
            return emissions.get(docId);
        }

        @Override
        public void close() {
            closes++;
            closed = true;
        }
    }

    private static final class CountingFieldCursor implements StreamingFieldPostings {
        final List<List<TermEntry>> emissions;
        int closes;
        boolean closed;

        CountingFieldCursor(List<List<TermEntry>> emissions) {
            this.emissions = emissions;
        }

        @Override
        public List<TermEntry> advance(int docId) throws IOException {
            if (closed) throw new IOException("closed");
            if (docId >= emissions.size()) return Collections.emptyList();
            return emissions.get(docId);
        }

        @Override
        public void close() {
            closes++;
            closed = true;
        }
    }

    /**
     * Minimal {@link LuceneLeafReader} that records how many times each open/build
     * hook was called, so tests can assert single-build behavior.
     */
    private static final class StubReader implements LuceneLeafReader {
        final Map<String, StreamingMultiTermPostings> multi;
        final Map<String, StreamingFieldPostings> positional;
        final Map<String, Map<Integer, Long>> numeric;
        int openMultiCalls;
        int openPositionalCalls;
        int buildNumericCalls;

        StubReader(Map<String, StreamingMultiTermPostings> multi,
                   Map<String, StreamingFieldPostings> positional,
                   Map<String, Map<Integer, Long>> numeric) {
            this.multi = multi;
            this.positional = positional;
            this.numeric = numeric;
        }

        @Override
        public StreamingMultiTermPostings openStreamingMultiTermPostings(String fieldName) {
            openMultiCalls++;
            return multi.get(fieldName);
        }

        @Override
        public StreamingFieldPostings openStreamingFieldPostings(String fieldName) {
            openPositionalCalls++;
            return positional.get(fieldName);
        }

        @Override
        public Map<Integer, Long> buildNumericTermIndex(String fieldName) {
            buildNumericCalls++;
            return numeric.getOrDefault(fieldName, Collections.emptyMap());
        }

        // ---- unused on this test path ----
        @Override public LuceneLeafReader newView() { return this; }
        @Override public org.opensearch.migrations.bulkload.lucene.BitSetConverter.FixedLengthBitSet getLiveDocs() {
            return null;
        }
        @Override public int maxDoc() { return 0; }
        @Override public String getSegmentName() { return "stub"; }
        @Override public String getSegmentInfoString() { return "stub-info"; }
        @Override public String getContextString() { return "stub-ctx"; }
        @Override public LuceneDocument document(int docId) { return null; }
        @Override public Iterable<DocValueFieldInfo> getDocValueFields() { return List.of(); }
        @Override public Object getDocValue(int docId, DocValueFieldInfo fieldInfo) { return null; }
        @Override public List<byte[]> getPointValues(int docId, String fieldName) { return null; }
    }

    // -------------------------------------------------------------------------
    //  Multi-term cursor: lazy build, caching, close lifecycle
    // -------------------------------------------------------------------------

    @Test
    void getMultiTermsForDocument_buildsCursorOnce_cachesResult() throws IOException {
        CountingMultiTermCursor cursor = new CountingMultiTermCursor(List.of(
            List.of("a"),
            List.of("b", "b")
        ));
        StubReader reader = new StubReader(
            Map.of("field1", cursor),
            Map.of(),
            Map.of()
        );

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertEquals(List.of("a"),       idx.getMultiTermsForDocument(reader, 0, "field1"));
            assertEquals(List.of("b", "b"),  idx.getMultiTermsForDocument(reader, 1, "field1"));
            // Cursor was opened exactly once even though we called twice
            assertEquals(1, reader.openMultiCalls);
        }
        // Close was propagated to the underlying cursor
        assertEquals(1, cursor.closes);
    }

    @Test
    void getMultiTermsForDocument_nullFromOpener_isNegativeCached() throws IOException {
        StubReader reader = new StubReader(Map.of(), Map.of(), Map.of()); // unknown field

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertTrue(idx.getMultiTermsForDocument(reader, 0, "missing").isEmpty());
            assertTrue(idx.getMultiTermsForDocument(reader, 1, "missing").isEmpty());
            assertTrue(idx.getMultiTermsForDocument(reader, 2, "missing").isEmpty());
            // Negative answer cached: opener called exactly once
            assertEquals(1, reader.openMultiCalls);
        }
    }

    @Test
    void getMultiTermsForDocument_distinctFields_independentCursors() throws IOException {
        CountingMultiTermCursor c1 = new CountingMultiTermCursor(List.of(List.of("alpha")));
        CountingMultiTermCursor c2 = new CountingMultiTermCursor(List.of(List.of("beta")));
        StubReader reader = new StubReader(Map.of("f1", c1, "f2", c2), Map.of(), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertEquals(List.of("alpha"), idx.getMultiTermsForDocument(reader, 0, "f1"));
            assertEquals(List.of("beta"),  idx.getMultiTermsForDocument(reader, 0, "f2"));
            assertEquals(2, reader.openMultiCalls);
        }
        assertEquals(1, c1.closes);
        assertEquals(1, c2.closes);
    }

    // -------------------------------------------------------------------------
    //  Single-term: reduces to multi-term cursor's first emit
    // -------------------------------------------------------------------------

    @Test
    void getSingleTermForDocument_returnsFirstEmittedTerm() throws IOException {
        CountingMultiTermCursor cursor = new CountingMultiTermCursor(List.of(
            List.of("only"),
            List.of("first", "second")
        ));
        StubReader reader = new StubReader(Map.of("kw", cursor), Map.of(), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertEquals("only",  idx.getSingleTermForDocument(reader, 0, "kw"));
            assertEquals("first", idx.getSingleTermForDocument(reader, 1, "kw"));
            // Both calls share the same cursor (no separate single-term cache)
            assertEquals(1, reader.openMultiCalls);
        }
    }

    @Test
    void getSingleTermForDocument_emptyEmission_returnsNull() throws IOException {
        CountingMultiTermCursor cursor = new CountingMultiTermCursor(List.of(
            Collections.emptyList()
        ));
        StubReader reader = new StubReader(Map.of("kw", cursor), Map.of(), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertNull(idx.getSingleTermForDocument(reader, 0, "kw"));
        }
    }

    // -------------------------------------------------------------------------
    //  Positional streaming cursor: lazy build, close lifecycle
    // -------------------------------------------------------------------------

    @Test
    void getTermEntriesForDocument_lazilyBuildsCursorOnce() throws IOException {
        TermEntry te = new TermEntry("hello", 0, TermEntry.NO_OFFSET, TermEntry.NO_OFFSET);
        CountingFieldCursor cursor = new CountingFieldCursor(List.of(List.of(te)));
        StubReader reader = new StubReader(Map.of(), Map.of("text", cursor), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            List<TermEntry> first = idx.getTermEntriesForDocument(reader, 0, "text");
            List<TermEntry> second = idx.getTermEntriesForDocument(reader, 1, "text");
            assertEquals(1, first.size());
            assertEquals("hello", first.get(0).term());
            assertTrue(second.isEmpty()); // doc 1 has nothing
            assertEquals(1, reader.openPositionalCalls);
        }
        assertEquals(1, cursor.closes);
    }

    @Test
    void getTermsForDocument_returnsTermStringsFromCursor() throws IOException {
        TermEntry te0 = new TermEntry("foo", 0, TermEntry.NO_OFFSET, TermEntry.NO_OFFSET);
        TermEntry te1 = new TermEntry("bar", 1, TermEntry.NO_OFFSET, TermEntry.NO_OFFSET);
        CountingFieldCursor cursor = new CountingFieldCursor(List.of(List.of(te0, te1)));
        StubReader reader = new StubReader(Map.of(), Map.of("text", cursor), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertEquals(List.of("foo", "bar"), idx.getTermsForDocument(reader, 0, "text"));
        }
    }

    @Test
    void getTermEntriesForDocument_nullFromOpener_isNegativeCached() throws IOException {
        StubReader reader = new StubReader(Map.of(), Map.of(), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertTrue(idx.getTermEntriesForDocument(reader, 0, "no-pos-field").isEmpty());
            assertTrue(idx.getTermEntriesForDocument(reader, 1, "no-pos-field").isEmpty());
            assertEquals(1, reader.openPositionalCalls);
        }
    }

    // -------------------------------------------------------------------------
    //  Numeric index: eager build, cached per field
    // -------------------------------------------------------------------------

    @Test
    void getNumericForDocument_buildsAndCachesPerField() throws IOException {
        StubReader reader = new StubReader(
            Map.of(),
            Map.of(),
            Map.of("score", Map.of(0, 42L, 1, 100L))
        );

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertEquals(42L,  idx.getNumericForDocument(reader, 0, "score"));
            assertEquals(100L, idx.getNumericForDocument(reader, 1, "score"));
            // Cached: built only once
            assertEquals(1, reader.buildNumericCalls);
        }
    }

    @Test
    void getNumericForDocument_unknownDoc_returnsNull() throws IOException {
        StubReader reader = new StubReader(
            Map.of(),
            Map.of(),
            Map.of("score", Map.of(0, 42L))
        );

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            assertNull(idx.getNumericForDocument(reader, 99, "score"));
        }
    }

    // -------------------------------------------------------------------------
    //  Close lifecycle — methods after close()
    // -------------------------------------------------------------------------

    @Test
    void afterClose_allMethodsThrowIOException() throws IOException {
        CountingMultiTermCursor multi = new CountingMultiTermCursor(List.of(List.of("x")));
        CountingFieldCursor positional = new CountingFieldCursor(List.of(List.of()));
        StubReader reader = new StubReader(
            Map.of("a", multi),
            Map.of("b", positional),
            Map.of("c", Map.of(0, 1L))
        );

        SegmentTermIndex idx = new SegmentTermIndex();
        // Touch each cache so they're populated and then closed
        idx.getMultiTermsForDocument(reader, 0, "a");
        idx.getTermEntriesForDocument(reader, 0, "b");
        idx.getNumericForDocument(reader, 0, "c");
        idx.close();

        assertThrows(IOException.class, () -> idx.getMultiTermsForDocument(reader, 1, "a"));
        assertThrows(IOException.class, () -> idx.getTermEntriesForDocument(reader, 1, "b"));
        assertThrows(IOException.class, () -> idx.getNumericForDocument(reader, 1, "c"));
        assertThrows(IOException.class, () -> idx.getSingleTermForDocument(reader, 1, "a"));
        assertThrows(IOException.class, () -> idx.getTermsForDocument(reader, 1, "b"));
    }

    @Test
    void doubleClose_isIdempotent_cursorsClosedOnce() throws IOException {
        CountingMultiTermCursor multi = new CountingMultiTermCursor(List.of(List.of("x")));
        CountingFieldCursor positional = new CountingFieldCursor(List.of(List.of()));
        StubReader reader = new StubReader(
            Map.of("a", multi),
            Map.of("b", positional),
            Map.of()
        );

        SegmentTermIndex idx = new SegmentTermIndex();
        idx.getMultiTermsForDocument(reader, 0, "a");
        idx.getTermEntriesForDocument(reader, 0, "b");

        idx.close();
        idx.close(); // must not re-close cursors

        assertEquals(1, multi.closes);
        assertEquals(1, positional.closes);
    }

    @Test
    void close_withFailingCursorClose_continuesToCloseOthers() throws IOException {
        // Verify the swallow-and-log behavior in close() so one bad field can't
        // prevent the others from releasing.
        StreamingMultiTermPostings boom = new StreamingMultiTermPostings() {
            @Override public List<String> advance(int docId) { return List.of("x"); }
            @Override public void close() { throw new RuntimeException("boom"); }
        };
        CountingMultiTermCursor good = new CountingMultiTermCursor(List.of(List.of("y")));
        StubReader reader = new StubReader(
            Map.of("bad", boom, "good", good),
            Map.of(),
            Map.of()
        );

        SegmentTermIndex idx = new SegmentTermIndex();
        idx.getMultiTermsForDocument(reader, 0, "bad");
        idx.getMultiTermsForDocument(reader, 0, "good");

        // close() must not throw, despite "bad" cursor's close() throwing
        try {
            idx.close();
        } catch (Exception e) {
            fail("close() should swallow per-cursor exceptions: " + e);
        }
        assertEquals(1, good.closes);
    }

    // -------------------------------------------------------------------------
    //  Sanity: open() never called for a cached field on subsequent accesses
    // -------------------------------------------------------------------------

    @Test
    void openMultiTerm_calledOncePerField_acrossManyDocs() throws IOException {
        CountingMultiTermCursor cursor = new CountingMultiTermCursor(List.of(
            List.of("d0"), List.of("d1"), List.of("d2"), List.of("d3"), List.of("d4")
        ));
        StubReader reader = new StubReader(Map.of("k", cursor), Map.of(), Map.of());

        try (SegmentTermIndex idx = new SegmentTermIndex()) {
            for (int doc = 0; doc < 5; doc++) {
                List<String> terms = idx.getMultiTermsForDocument(reader, doc, "k");
                assertNotNull(terms);
                assertEquals(1, terms.size());
            }
            assertEquals(1, reader.openMultiCalls);
        }
    }
}
