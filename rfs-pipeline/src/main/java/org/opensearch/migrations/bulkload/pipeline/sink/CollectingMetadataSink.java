package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import reactor.core.publisher.Mono;

/**
 * A collecting {@link MetadataSink} for testing the metadata reading side.
 *
 * <p>Captures all metadata written, allowing assertions on what was produced.
 */
public class CollectingMetadataSink implements MetadataSink {

    private final List<GlobalMetadataSnapshot> globalMetadata = new CopyOnWriteArrayList<>();
    private final List<IndexMetadataSnapshot> createdIndices = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> writeGlobalMetadata(GlobalMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> globalMetadata.add(metadata));
    }

    @Override
    public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> createdIndices.add(metadata));
    }

    /** All global metadata snapshots written. */
    public List<GlobalMetadataSnapshot> getGlobalMetadata() {
        return Collections.unmodifiableList(globalMetadata);
    }

    /** All indices created via {@link #createIndex}. */
    public List<IndexMetadataSnapshot> getCreatedIndices() {
        return Collections.unmodifiableList(createdIndices);
    }

    /** Reset all collected state. */
    public void reset() {
        globalMetadata.clear();
        createdIndices.clear();
    }
}
