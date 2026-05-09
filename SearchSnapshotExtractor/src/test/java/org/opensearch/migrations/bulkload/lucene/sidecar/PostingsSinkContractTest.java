package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests pinning the two tiny value types on the main-sourceset boundary
 * between a version-specific Lucene reader and the version-agnostic sidecar:
 * {@link BytesRefLike} (holds a subrange of a reusable byte buffer) and
 * {@link PostingsSink} (receives {@code (termId, docId, positions[])} emissions).
 */
class PostingsSinkContractTest {

    @Test
    void bytesRefLike_copiesBytesDefensivelyOnToByteArray() {
        byte[] src = "hello world".getBytes(StandardCharsets.UTF_8);
        BytesRefLike ref = new BytesRefLike(src, 6, 5); // "world"
        byte[] out = ref.toByteArray();
        assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), out);
        // Mutating source must not change the returned copy — sinks that retain term bytes
        // via toByteArray() must be safe against the reader reusing its buffer.
        src[6] = 'X';
        assertEquals('w', out[0]);
    }

    @Test
    void bytesRefLike_equalsAndHashCodeOverValueNotIdentity() {
        byte[] a = "foo".getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[]{'x', 'f', 'o', 'o', 'x'};
        BytesRefLike r1 = new BytesRefLike(a, 0, 3);
        BytesRefLike r2 = new BytesRefLike(b, 1, 3);
        BytesRefLike r3 = new BytesRefLike(a, 0, 2); // "fo"
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        // bytes() returns the original buffer reference — no defensive copy on access, only on toByteArray()
        assertSame(a, r1.bytes());
    }

    private static void assertSame(Object expected, Object actual) {
        assertTrue(expected == actual, "Expected same identity, got different instances");
    }

    @Test
    void bytesRefLike_rejectsNegativeOffsetOrLength() {
        byte[] src = "abc".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new BytesRefLike(src, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BytesRefLike(src, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new BytesRefLike(src, 1, 3)); // 1+3 > 3
        assertThrows(NullPointerException.class, () -> new BytesRefLike(null, 0, 0));
    }

    @Test
    void postingsSink_recordsAllEmissionsInOrder() throws Exception {
        RecordingSink sink = new RecordingSink();
        byte[] t1 = "aa".getBytes(StandardCharsets.UTF_8);
        byte[] t2 = "bb".getBytes(StandardCharsets.UTF_8);
        int aaId = sink.registerTerm(new BytesRefLike(t1, 0, 2));
        int bbId = sink.registerTerm(new BytesRefLike(t2, 0, 2));
        sink.accept(aaId, 3, new int[]{0, 2}, 2);
        sink.accept(bbId, 3, new int[]{1}, 1);
        sink.accept(aaId, 7, new int[]{4}, 1);

        assertEquals(0, aaId);
        assertEquals(1, bbId);
        assertEquals(List.of("aa:3@[0,2]", "bb:3@[1]", "aa:7@[4]"), sink.log);
    }

    /** Minimal sink that records every call for assertion. */
    private static final class RecordingSink implements PostingsSink {
        private final List<String> registered = new ArrayList<>();
        private final List<String> log = new ArrayList<>();

        @Override
        public int registerTerm(BytesRefLike term) {
            registered.add(new String(term.toByteArray(), StandardCharsets.UTF_8));
            return registered.size() - 1;
        }

        @Override
        public void accept(int termId, int docId, int[] positions, int[] startOffsets, int[] endOffsets, int positionCount) {
            StringBuilder sb = new StringBuilder();
            sb.append(registered.get(termId)).append(':').append(docId).append("@[");
            for (int i = 0; i < positionCount; i++) {
                if (i > 0) sb.append(',');
                sb.append(positions[i]);
            }
            sb.append(']');
            log.add(sb.toString());
        }
    }
}
