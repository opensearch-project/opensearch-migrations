package org.opensearch.migrations.bulkload.pipeline;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.sink.CollectingMetadataSink;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticMetadataSource;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MetadataMigrationPipeline}.
 */
class MetadataMigrationPipelineTest {

    @Test
    void migratesGlobalMetadata() {
        var source = new SyntheticMetadataSource(List.of("idx-1", "idx-2"), 3);
        var sink = new CollectingMetadataSink();
        var pipeline = new MetadataMigrationPipeline(source, sink);

        StepVerifier.create(pipeline.migrateGlobalMetadata())
            .verifyComplete();

        assertEquals(1, sink.getGlobalMetadata().size());
        assertNotNull(sink.getGlobalMetadata().get(0).templates());
    }

    @Test
    void migratesSingleIndexMetadata() {
        var source = new SyntheticMetadataSource(List.of("my-index"), 5);
        var sink = new CollectingMetadataSink();
        var pipeline = new MetadataMigrationPipeline(source, sink);

        StepVerifier.create(pipeline.migrateIndexMetadata("my-index"))
            .verifyComplete();

        assertEquals(1, sink.getCreatedIndices().size());
        var idx = sink.getCreatedIndices().get(0);
        assertEquals("my-index", idx.indexName());
        assertEquals(5, idx.numberOfShards());
    }

    @Test
    void migrateAllProcessesGlobalAndAllIndices() {
        var indices = List.of("idx-a", "idx-b", "idx-c");
        var source = new SyntheticMetadataSource(indices, 2);
        var sink = new CollectingMetadataSink();
        var pipeline = new MetadataMigrationPipeline(source, sink);

        StepVerifier.create(pipeline.migrateAll())
            .expectNext("idx-a")
            .expectNext("idx-b")
            .expectNext("idx-c")
            .verifyComplete();

        assertEquals(1, sink.getGlobalMetadata().size(), "Global metadata written once");
        assertEquals(3, sink.getCreatedIndices().size(), "All 3 indices created");
    }

    @Test
    void migrateAllWithNoIndices() {
        var source = new SyntheticMetadataSource(List.of(), 1);
        var sink = new CollectingMetadataSink();
        var pipeline = new MetadataMigrationPipeline(source, sink);

        StepVerifier.create(pipeline.migrateAll())
            .verifyComplete();

        assertEquals(1, sink.getGlobalMetadata().size(), "Global metadata still written");
        assertEquals(0, sink.getCreatedIndices().size(), "No indices to create");
    }

    @Test
    void rejectsNullSource() {
        var sink = new CollectingMetadataSink();
        assertThrows(NullPointerException.class,
            () -> new MetadataMigrationPipeline(null, sink));
    }

    @Test
    void rejectsNullSink() {
        var source = new SyntheticMetadataSource(List.of(), 1);
        assertThrows(NullPointerException.class,
            () -> new MetadataMigrationPipeline(source, null));
    }
}
