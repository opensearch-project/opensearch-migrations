package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for {@link SidecarBuilder} + {@link SidecarReader} round-trip.
 *
 * <p>The builder consumes term-major emissions ({@code (termId, docId, positions[])}
 * in term-ascending and docId-ascending order) and produces a disk-backed sidecar
 * file that the reader opens via mmap. These tests fuzz the builder with randomized
 * inputs and assert that, for every docId, the reader returns exactly the
 * position-ordered term list that the reference in-memory implementation produces,
 * with same-position duplicates deduplicated by keeping the longest token.
 *
 * <p>Build parameters are deliberately pushed to their limits:
 *   <ul>
 *     <li>Tiny sort buffers force multi-run external sort with k-way merge.
 *     <li>Random inputs include ties on (docId, position) to pin the
 *         tie-break rule (longest token wins).
 *     <li>Sparse segments (most docs empty for the field) verify the doc-index
 *         correctly encodes the "no tokens" sentinel.
 *   </ul>
 */
class SidecarBuilderRoundTripTest {

    private Path spillDir;

    @BeforeEach
    void setUp() throws IOException {
        spillDir = Files.createTempDirectory("sidecar-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (spillDir != null) deleteRecursive(spillDir);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void smallFixedInput_roundTrip() throws IOException {
        List<Emission> stream = List.of(
            e("apple",  0, 0),
            e("apple",  0, 5),
            e("apple",  2, 1),
            e("banana", 0, 3),
            e("banana", 1, 0),
            e("cherry", 2, 4)
        );
        SidecarReader reader = buildAndOpen(stream, /*maxDoc=*/3, /*sortBufferBytes=*/1024);
        try {
            assertEquals(List.of("apple", "banana", "apple"), reader.getTermStrings(0));
            assertEquals(List.of("banana"),                   reader.getTermStrings(1));
            assertEquals(List.of("apple", "cherry"),          reader.getTermStrings(2));
            assertEquals(Collections.emptyList(),             reader.getTermStrings(3)); // out of range
        } finally {
            reader.close();
        }
    }

    @Test
    void fuzz_matchesInHeapReference_smallSortBuffer() throws IOException {
        Random rng = new Random(0xBADF00DL);
        List<Emission> stream = randomStream(rng, /*numTerms=*/500, /*numDocs=*/200, /*avgPositionsPerDocTerm=*/3);
        Map<Integer, List<String>> reference = inHeapReference(stream);

        // Tiny sort buffer (24 bytes = 1 record) forces a multi-run merge to exercise k-way merge.
        SidecarReader reader = buildAndOpen(stream, 200, 24);
        try {
            for (int doc = 0; doc < 200; doc++) {
                List<String> got  = reader.getTermStrings(doc);
                List<String> want = reference.getOrDefault(doc, Collections.emptyList());
                assertEquals(want, got, "Mismatch for docId=" + doc);
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Same-position tokens with the same length: the one with the smaller termId (i.e. the one
     * that comes first in the sidecar's natural sort order) wins. The other is discarded.
     */
    @Test
    void tiesAtSamePosition_sameLength_firstTermIdWins() throws IOException {
        // "aa" is termId 0, "bb" is termId 1. Both at doc 0 position 5, both length 2.
        // Dedup keeps the first encountered in position-group scan (termId 0 = "aa").
        List<Emission> stream = List.of(
            e("aa", 0, 5),
            e("bb", 0, 5)
        );
        SidecarReader reader = buildAndOpen(stream, 1, 1024);
        try {
            assertEquals(List.of("aa"), reader.getTermStrings(0));
        } finally {
            reader.close();
        }
    }

    /**
     * Same-position tokens where one is the compound form and the others are sub-tokens —
     * the pattern produced by word_delimiter_graph and similar token filters.
     * The longest token wins and sub-tokens are discarded.
     */
    @Test
    void samePosition_deduplicates_longestToken() throws IOException {
        // Simulate word_delimiter_graph on "2000 09":
        //   position 4: "2000 09" (len 7), "2000" (len 4), "09" (len 2)
        // All share the same position. Longest = "2000 09".
        List<Emission> stream = List.of(
            e("2000 09", 0, 4),
            e("2000",    0, 4),
            e("09",      0, 4),
            e("date",    0, 0),   // surrounding context
            e("sep",     0, 3)
        );
        SidecarReader reader = buildAndOpen(stream, 1, 1024);
        try {
            List<String> result = reader.getTermStrings(0);
            // Position order: 0="date", 3="sep", 4="2000 09" (winner — sub-tokens discarded)
            assertEquals(List.of("date", "sep", "2000 09"), result,
                "sub-tokens at same position must be deduplicated, keeping the longest");
        } finally {
            reader.close();
        }
    }

    /**
     * When offsets are stored, the reader returns TermEntry objects with the correct
     * character-span data, and joinWithOffsets preserves inter-token gap spacing.
     */
    @Test
    void offsetGap_preservesDoubleSpaces() throws IOException {
        // Simulate "date  tue  26" with double spaces (offsets present):
        //   "date"  start=0  end=4
        //   "tue"   start=6  end=9   (gap of 2 = "  ")
        //   "26"    start=11 end=13  (gap of 2 = "  ")
        List<EmissionWithOffsets> stream = List.of(
            eo("date", 0, 0,  0,  4),
            eo("tue",  0, 1,  6,  9),
            eo("26",   0, 2, 11, 13)
        );
        SidecarReader reader = buildAndOpenWithOffsets(stream, 1, 1024);
        try {
            List<SidecarReader.TermEntry> entries = reader.get(0);
            assertEquals(3, entries.size());
            assertEquals(new SidecarReader.TermEntry("date",  0,  4), entries.get(0));
            assertEquals(new SidecarReader.TermEntry("tue",   6,  9), entries.get(1));
            assertEquals(new SidecarReader.TermEntry("26",   11, 13), entries.get(2));

            // LuceneLeafReader.joinWithOffsets should produce "date  tue  26"
            String joined = org.opensearch.migrations.bulkload.lucene.LuceneLeafReader
                    .joinWithOffsets(entries);
            assertEquals("date  tue  26", joined,
                "double-space gaps from character offsets must be preserved in reconstruction");
        } finally {
            reader.close();
        }
    }

    /**
     * When NO_OFFSET sentinel is present, joinWithOffsets falls back to single-space join.
     */
    @Test
    void noOffset_fallsBackToSingleSpaceJoin() throws IOException {
        List<SidecarReader.TermEntry> entries = List.of(
            new SidecarReader.TermEntry("hello", PostingsSink.NO_OFFSET, PostingsSink.NO_OFFSET),
            new SidecarReader.TermEntry("world", PostingsSink.NO_OFFSET, PostingsSink.NO_OFFSET)
        );
        String joined = org.opensearch.migrations.bulkload.lucene.LuceneLeafReader
                .joinWithOffsets(entries);
        assertEquals("hello world", joined,
            "NO_OFFSET sentinel must trigger single-space fallback");
    }

    @Test
    void sparseDocs_returnEmptyForUnemittedDocs() throws IOException {
        List<Emission> stream = List.of(
            e("x", 3, 0),
            e("x", 7, 0)
        );
        SidecarReader reader = buildAndOpen(stream, 10, 1024);
        try {
            for (int d = 0; d < 10; d++) {
                if (d == 3 || d == 7) assertEquals(List.of("x"), reader.getTermStrings(d));
                else assertEquals(Collections.emptyList(), reader.getTermStrings(d));
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void emptyStream_producesValidEmptyReader() throws IOException {
        SidecarReader reader = buildAndOpen(Collections.emptyList(), 5, 1024);
        try {
            for (int d = 0; d < 5; d++) {
                assertEquals(Collections.emptyList(), reader.getTermStrings(d));
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void buildClosesFilesOnReadyForReaderOpen() throws IOException {
        List<Emission> stream = List.of(e("alpha", 0, 0));
        SidecarReader reader = buildAndOpen(stream, 1, 1024);
        try {
            assertEquals(List.of("alpha"), reader.getTermStrings(0));
        } finally {
            reader.close();
        }
        assertTrue(Files.exists(spillDir), "Spill dir should survive reader close");
    }

    // ---- helpers --------------------------------------------------------

    private SidecarReader buildAndOpen(List<Emission> stream, int maxDoc, int sortBufferBytes) throws IOException {
        // Convert to EmissionWithOffsets with NO_OFFSET sentinels.
        List<EmissionWithOffsets> withOffsets = new ArrayList<>(stream.size());
        for (Emission e : stream) {
            withOffsets.add(new EmissionWithOffsets(e.term, e.docId, e.position,
                    PostingsSink.NO_OFFSET, PostingsSink.NO_OFFSET));
        }
        return buildAndOpenWithOffsets(withOffsets, maxDoc, sortBufferBytes);
    }

    private SidecarReader buildAndOpenWithOffsets(List<EmissionWithOffsets> stream,
                                                  int maxDoc, int sortBufferBytes) throws IOException {
        SidecarBuilder builder = new SidecarBuilder(spillDir, sortBufferBytes, maxDoc);
        Map<String, Integer> termIds = new HashMap<>();
        List<String> sortedDistinctTerms = new ArrayList<>(new java.util.TreeSet<>(
            stream.stream().map(e -> e.term).toList()
        ));
        for (String t : sortedDistinctTerms) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            int id = builder.registerTerm(new BytesRefLike(bytes, 0, bytes.length));
            termIds.put(t, id);
        }
        // Sort by (termId, docId, position) as the sink contract requires.
        List<EmissionWithOffsets> mutable = new ArrayList<>(stream);
        mutable.sort(Comparator.<EmissionWithOffsets>comparingInt(e -> termIds.get(e.term))
                               .thenComparingInt(e -> e.docId)
                               .thenComparingInt(e -> e.position));
        int i = 0;
        while (i < mutable.size()) {
            EmissionWithOffsets head = mutable.get(i);
            int termId = termIds.get(head.term);
            int docId  = head.docId;
            int[] positions    = new int[16];
            int[] startOffsets = new int[16];
            int[] endOffsets   = new int[16];
            int n = 0;
            while (i < mutable.size()
                    && termIds.get(mutable.get(i).term) == termId
                    && mutable.get(i).docId == docId) {
                if (n == positions.length) {
                    positions    = Arrays.copyOf(positions,    positions.length * 2);
                    startOffsets = Arrays.copyOf(startOffsets, startOffsets.length * 2);
                    endOffsets   = Arrays.copyOf(endOffsets,   endOffsets.length * 2);
                }
                positions[n]    = mutable.get(i).position;
                startOffsets[n] = mutable.get(i).startOffset;
                endOffsets[n]   = mutable.get(i).endOffset;
                n++;
                i++;
            }
            builder.accept(termId, docId, positions, startOffsets, endOffsets, n);
        }
        return builder.buildAndOpenReader();
    }

    /**
     * Reference model: for each position group, keep only the longest term.
     * Ties (same length) are broken by termId ASC (i.e. first encountered in sorted order).
     */
    private static Map<Integer, List<String>> inHeapReference(List<Emission> stream) {
        List<String> sortedDistinctTerms = new ArrayList<>(new java.util.TreeSet<>(
            stream.stream().map(e -> e.term).toList()
        ));
        Map<String, Integer> termIds = new HashMap<>();
        for (int i = 0; i < sortedDistinctTerms.size(); i++) termIds.put(sortedDistinctTerms.get(i), i);

        // For each (docId, position), keep the longest term; break ties by termId ASC.
        // Key: docId -> (position -> best term)
        Map<Integer, TreeMap<Integer, String>> byDoc = new HashMap<>();
        for (Emission em : stream) {
            byDoc.computeIfAbsent(em.docId, k -> new TreeMap<>())
                 .merge(em.position, em.term, (existing, candidate) -> {
                     int existingLen   = existing.length();
                     int candidateLen  = candidate.length();
                     if (candidateLen > existingLen) return candidate;
                     if (candidateLen == existingLen) {
                         // Same length: keep the one with smaller termId (= bytes-ascending order).
                         return termIds.get(existing) <= termIds.get(candidate) ? existing : candidate;
                     }
                     return existing;
                 });
        }
        Map<Integer, List<String>> out = new HashMap<>();
        byDoc.forEach((d, map) -> out.put(d, new ArrayList<>(map.values())));
        return out;
    }

    private static List<Emission> randomStream(Random rng, int numTerms, int numDocs, int avgPositionsPerDocTerm) {
        List<String> terms = new ArrayList<>(numTerms);
        for (int i = 0; i < numTerms; i++) terms.add("t" + String.format("%05d", i));
        List<Emission> out = new ArrayList<>();
        for (String term : terms) {
            int docsWithTerm = 1 + rng.nextInt(Math.max(1, numDocs / 4));
            boolean[] chosen = new boolean[numDocs];
            for (int k = 0; k < docsWithTerm; k++) chosen[rng.nextInt(numDocs)] = true;
            for (int doc = 0; doc < numDocs; doc++) {
                if (!chosen[doc]) continue;
                int numPos = 1 + rng.nextInt(avgPositionsPerDocTerm * 2);
                java.util.TreeSet<Integer> pos = new java.util.TreeSet<>();
                while (pos.size() < numPos) pos.add(rng.nextInt(1000));
                for (int p : pos) out.add(e(term, doc, p));
            }
        }
        return out;
    }

    private static Emission e(String term, int docId, int pos) {
        return new Emission(term, docId, pos);
    }

    private static EmissionWithOffsets eo(String term, int docId, int pos, int startOffset, int endOffset) {
        return new EmissionWithOffsets(term, docId, pos, startOffset, endOffset);
    }

    private static final class Emission {
        final String term;
        final int docId;
        final int position;
        Emission(String term, int docId, int position) {
            this.term = term;
            this.docId = docId;
            this.position = position;
        }
    }

    private static final class EmissionWithOffsets {
        final String term;
        final int docId;
        final int position;
        final int startOffset;
        final int endOffset;
        EmissionWithOffsets(String term, int docId, int position, int startOffset, int endOffset) {
            this.term = term;
            this.docId = docId;
            this.position = position;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }
}
