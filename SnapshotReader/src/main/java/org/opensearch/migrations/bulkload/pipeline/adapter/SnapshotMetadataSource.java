package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.source.MetadataSource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Real {@link MetadataSource} adapter that reads metadata from a snapshot
 * via the existing {@link SnapshotExtractor}.
 */
@Slf4j
public class SnapshotMetadataSource implements MetadataSource {

    private final SnapshotExtractor extractor;
    private final String snapshotName;

    public SnapshotMetadataSource(SnapshotExtractor extractor, String snapshotName) {
        this.extractor = extractor;
        this.snapshotName = snapshotName;
    }

    @Override
    public GlobalMetadataSnapshot readGlobalMetadata() {
        var global = extractor.getSnapshotReader().getGlobalMetadata().fromRepo(snapshotName);
        List<String> indices = extractor.listIndices(snapshotName);
        return new GlobalMetadataSnapshot(
            global.getTemplates(),
            global.getIndexTemplates(),
            global.getComponentTemplates(),
            indices
        );
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        var meta = extractor.getSnapshotReader().getIndexMetadata()
            .fromRepo(snapshotName, indexName);
        return new IndexMetadataSnapshot(
            indexName,
            meta.getNumberOfShards(),
            meta.getSettings().path("number_of_replicas").asInt(0),
            (ObjectNode) meta.getMappings(),
            (ObjectNode) meta.getSettings(),
            (ObjectNode) meta.getAliases()
        );
    }
}
