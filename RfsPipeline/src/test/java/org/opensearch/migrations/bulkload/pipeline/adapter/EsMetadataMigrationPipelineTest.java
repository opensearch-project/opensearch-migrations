package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EsMetadataMigrationPipelineTest {

    @Test
    void migrateAllLimitsConcurrentIndexMigrationsToFive() {
        var indices = List.of("index-1", "index-2", "index-3", "index-4", "index-5", "index-6");
        var globalMetadata = new GlobalMetadataSnapshot(null, null, null, indices);
        var globalMigration = Sinks.<Void>empty();
        var indexMigrations = new HashMap<String, Sinks.Empty<Void>>();
        indices.forEach(index -> indexMigrations.put(index, Sinks.empty()));

        var source = mock(GlobalMetadataSource.class);
        when(source.readGlobalMetadata()).thenReturn(globalMetadata);
        when(source.readIndexMetadata(anyString())).thenAnswer(invocation ->
            new IndexMetadataSnapshot(invocation.getArgument(0), 1, 0, null, null, null));

        var sink = mock(GlobalMetadataSink.class);
        when(sink.writeGlobalMetadata(globalMetadata)).thenReturn(globalMigration.asMono());
        when(sink.createIndex(any())).thenAnswer(invocation -> {
            IndexMetadataSnapshot metadata = invocation.getArgument(0);
            return indexMigrations.get(metadata.indexName()).asMono();
        });

        var pipeline = new EsMetadataMigrationPipeline(source, sink);

        StepVerifier.create(pipeline.migrateAll())
            .then(() -> verify(sink, never()).createIndex(any()))
            .then(globalMigration::tryEmitEmpty)
            .then(() -> verify(sink, times(5)).createIndex(any()))
            .then(indexMigrations.get("index-1")::tryEmitEmpty)
            .then(() -> verify(sink, times(6)).createIndex(any()))
            .then(() -> indices.stream().skip(1).forEach(index -> indexMigrations.get(index).tryEmitEmpty()))
            .expectNextCount(indices.size())
            .verifyComplete();
    }
}
