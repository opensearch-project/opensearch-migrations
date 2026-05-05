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
 * inputs and assert that, for every docId, {@code SidecarReader.get(docId)} returns
 * exactly the position-ordered term list that the reference in-memory
 * implementation (a {@code TreeMap<Integer, String>} per doc) would produce.
 *
 * <p>Build parameters are deliberately pushed to their limits:
 *   <ul>
 *     <li>Tiny sort buffers force multi-run external sort with k-way merge.
 *     <li>Random inputs include ties on (docId, position) to pin the
 *         tie-break rule (termId ASC).
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
        // Build input sorted as the sink contract requires:
        //   term-bytes ascending, within term docId ascending, positions ascending.
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
            assertEquals(List.of("apple", "banana", "apple"), reader.get(0));
            assertEquals(List.of("banana"),                   reader.get(1));
            assertEquals(List.of("apple", "cherry"),          reader.get(2));
            assertEquals(Collections.emptyList(),             reader.get(3)); // out of range
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
                List<String> got = reader.get(doc);
                List<String> want = reference.getOrDefault(doc, Collections.emptyList());
                assertEquals(want, got, "Mismatch for docId=" + doc);
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void tiesAtSamePosition_breakByTermIdAscending() throws IOException {
        // Term "aa" is termId 0 (registered first), term "bb" is termId 1. Both at doc 0 position 5.
        // Sink contract: termId ASC is the secondary key when positions tie.
        List<Emission> stream = List.of(
            e("aa", 0, 5),
            e("bb", 0, 5)
        );
        SidecarReader reader = buildAndOpen(stream, 1, 1024);
        try {
            assertEquals(List.of("aa", "bb"), reader.get(0));
        } finally {
            reader.close();
        }
    }

    @Test
    void sparseDocs_returnEmptyForUnemittedDocs() throws IOException {
        // Only docs 3 and 7 have tokens in a maxDoc=10 segment.
        List<Emission> stream = List.of(
            e("x", 3, 0),
            e("x", 7, 0)
        );
        SidecarReader reader = buildAndOpen(stream, 10, 1024);
        try {
            for (int d = 0; d < 10; d++) {
                if (d == 3 || d == 7) assertEquals(List.of("x"), reader.get(d));
                else assertEquals(Collections.emptyList(), reader.get(d));
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
                assertEquals(Collections.emptyList(), reader.get(d));
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
            assertEquals(List.of("alpha"), reader.get(0));
        } finally {
            reader.close();
        }
        // Reader close must delete nothing; the builder produces files that the caller owns.
        assertTrue(Files.exists(spillDir), "Spill dir should survive reader close");
    }

    // ---- helpers --------------------------------------------------------

    private SidecarReader buildAndOpen(List<Emission> stream, int maxDoc, int sortBufferBytes) throws IOException {
        SidecarBuilder builder = new SidecarBuilder(spillDir, sortBufferBytes, maxDoc);
        // Register terms in ascending-bytes order so termIds match what a real reader would assign.
        Map<String, Integer> termIds = new HashMap<>();
        List<String> sortedDistinctTerms = new ArrayList<>(new java.util.TreeSet<>(
            stream.stream().map(e -> e.term).toList()
        ));
        for (String t : sortedDistinctTerms) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            int id = builder.registerTerm(new BytesRefLike(bytes, 0, bytes.length));
            termIds.put(t, id);
        }
        // Group emissions into (termId, docId) runs with positions[] payloads.
        List<Emission> mutable = new ArrayList<>(stream);
        mutable.sort(Comparator.<Emission>comparingInt(e -> termIds.get(e.term))
                              .thenComparingInt(e -> e.docId)
                              .thenComparingInt(e -> e.position));
        int i = 0;
        while (i < mutable.size()) {
            Emission head = mutable.get(i);
            int termId = termIds.get(head.term);
            int docId = head.docId;
            int[] positions = new int[16];
            int n = 0;
            while (i < mutable.size()
                   && termIds.get(mutable.get(i).term) == termId
                   && mutable.get(i).docId == docId) {
                if (n == positions.length) positions = Arrays.copyOf(positions, positions.length * 2);
                positions[n++] = mutable.get(i).position;
                i++;
            }
            builder.accept(termId, docId, positions, n);
        }
        return builder.buildAndOpenReader();
    }

    private static Map<Integer, List<String>> inHeapReference(List<Emission> stream) {
        // Reference: TreeMap<Integer,String> per doc, sorted by position, ties broken by term bytes.
        Map<Integer, TreeMap<Long, String>> byDoc = new HashMap<>();
        // Assign termIds in the same order SidecarBuilder will (sorted bytes ascending).
        List<String> sortedDistinctTerms = new ArrayList<>(new java.util.TreeSet<>(
            stream.stream().map(e -> e.term).toList()
        ));
        Map<String, Integer> termIds = new HashMap<>();
        for (int i = 0; i < sortedDistinctTerms.size(); i++) termIds.put(sortedDistinctTerms.get(i), i);
        for (Emission em : stream) {
            // Secondary key: termId. Compose (position, termId) so duplicate positions with
            // different termIds preserve both with termId-ASC tiebreak.
            long key = ((long) em.position << 32) | (termIds.get(em.term) & 0xFFFFFFFFL);
            byDoc.computeIfAbsent(em.docId, k -> new TreeMap<>()).put(key, em.term);
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
            // Pick a random subset of docs this term appears in.
            int docsWithTerm = 1 + rng.nextInt(Math.max(1, numDocs / 4));
            boolean[] chosen = new boolean[numDocs];
            for (int k = 0; k < docsWithTerm; k++) chosen[rng.nextInt(numDocs)] = true;
            for (int doc = 0; doc < numDocs; doc++) {
                if (!chosen[doc]) continue;
                int numPos = 1 + rng.nextInt(avgPositionsPerDocTerm * 2);
                // Pick unique positions ASC.
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
}
