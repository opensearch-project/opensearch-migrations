package org.opensearch.migrations.bulkload.pipeline;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IR type validation and behavior.
 */
class IrTypesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class DocumentChangeTests {

        @Test
        void rejectsNullId() {
            assertThrows(NullPointerException.class,
                () -> new DocumentChange(null, null, null, null, DocumentChange.ChangeType.INDEX));
        }

        @Test
        void rejectsNullOperation() {
            assertThrows(NullPointerException.class,
                () -> new DocumentChange("id", null, null, null, null));
        }

        @Test
        void allowsNullTypeSourceRouting() {
            var doc = new DocumentChange("id", null, null, null, DocumentChange.ChangeType.DELETE);
            assertEquals("id", doc.id());
            assertNull(doc.type());
            assertNull(doc.source());
            assertNull(doc.routing());
        }
    }

    @Nested
    class ShardIdTests {

        @Test
        void rejectsNullSnapshotName() {
            assertThrows(NullPointerException.class,
                () -> new ShardId(null, "idx", 0));
        }

        @Test
        void rejectsNullIndexName() {
            assertThrows(NullPointerException.class,
                () -> new ShardId("snap", null, 0));
        }

        @Test
        void rejectsNegativeShardNumber() {
            assertThrows(IllegalArgumentException.class,
                () -> new ShardId("snap", "idx", -1));
        }

        @Test
        void toStringIsReadable() {
            var shard = new ShardId("my-snap", "my-index", 3);
            assertEquals("my-snap/my-index/3", shard.toString());
        }
    }

    @Nested
    class IndexMetadataSnapshotTests {

        @Test
        void rejectsNullIndexName() {
            assertThrows(NullPointerException.class,
                () -> new IndexMetadataSnapshot(null, 1, 0, null, null, null));
        }

        @Test
        void rejectsZeroShards() {
            assertThrows(IllegalArgumentException.class,
                () -> new IndexMetadataSnapshot("idx", 0, 0, null, null, null));
        }

        @Test
        void rejectsNegativeReplicas() {
            assertThrows(IllegalArgumentException.class,
                () -> new IndexMetadataSnapshot("idx", 1, -1, null, null, null));
        }

        @Test
        void allowsNullMappingsSettingsAliases() {
            var meta = new IndexMetadataSnapshot("idx", 1, 0, null, null, null);
            assertEquals("idx", meta.indexName());
            assertNull(meta.mappings());
        }
    }

    @Nested
    class GlobalMetadataSnapshotTests {

        @Test
        void rejectsNullIndices() {
            assertThrows(NullPointerException.class,
                () -> new GlobalMetadataSnapshot(null, null, null, null));
        }

        @Test
        void defensivelyCopiesIndicesList() {
            var mutableList = new java.util.ArrayList<>(List.of("a", "b"));
            var meta = new GlobalMetadataSnapshot(null, null, null, mutableList);
            mutableList.add("c");
            assertEquals(2, meta.indices().size(), "Should not be affected by external mutation");
        }

        @Test
        void indicesListIsImmutable() {
            var meta = new GlobalMetadataSnapshot(null, null, null, List.of("a"));
            assertThrows(UnsupportedOperationException.class,
                () -> meta.indices().add("b"));
        }
    }

    @Nested
    class ProgressCursorTests {

        @Test
        void rejectsNullShardId() {
            assertThrows(NullPointerException.class,
                () -> new ProgressCursor(null, 0, 0, 0));
        }

        @Test
        void rejectsNegativeDocsInBatch() {
            var shard = new ShardId("s", "i", 0);
            assertThrows(IllegalArgumentException.class,
                () -> new ProgressCursor(shard, 0, -1, 0));
        }

        @Test
        void rejectsNegativeBytesInBatch() {
            var shard = new ShardId("s", "i", 0);
            assertThrows(IllegalArgumentException.class,
                () -> new ProgressCursor(shard, 0, 0, -1));
        }
    }
}
