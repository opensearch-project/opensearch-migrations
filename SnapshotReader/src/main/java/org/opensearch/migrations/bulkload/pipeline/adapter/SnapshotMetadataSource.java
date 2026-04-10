package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;

import org.opensearch.migrations.bulkload.SnapshotExtractor;

import lombok.extern.slf4j.Slf4j;

/**
 * ES snapshot adapter for {@link GlobalMetadataSource}. Reads global metadata and
 * per-index metadata from an ES snapshot via {@link SnapshotExtractor}.
 */
@Slf4j
public class SnapshotMetadataSource implements GlobalMetadataSource {

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
        return IndexMetadataConverter.convert(indexName, meta);
    }
}
