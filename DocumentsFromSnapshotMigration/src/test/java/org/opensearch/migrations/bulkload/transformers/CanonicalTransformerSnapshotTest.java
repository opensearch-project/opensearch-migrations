package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.bulkload.version_os_2_11.IndexMetadataData_OS_2_11;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests CanonicalTransformer against real snapshot data from test-resources.
 */
@Slf4j
public class CanonicalTransformerSnapshotTest {

    @Test
    public void transformGlobalMetadata_AsExpected() throws Exception {
        TestResources.Snapshot snapshot = TestResources.SNAPSHOT_ES_7_10_BWC_CHECK;
        Version version = Version.fromString("ES 7.10");

        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        final var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(version, repo, false);

        CanonicalTransformer transformer = new CanonicalTransformer(2);

        GlobalMetadata globalMetadata = sourceResourceProvider.getGlobalMetadata().fromRepo(snapshot.name);
        GlobalMetadata transformedGlobalMetadata = transformer.transformGlobalMetadata(globalMetadata);
        GlobalMetadataData_OS_2_11 finalMetadata = new GlobalMetadataData_OS_2_11(
            transformedGlobalMetadata.toObjectNode()
        );

        assertNotNull(finalMetadata.getTemplates(), "Templates should not be null");
        log.info("Transformed global metadata templates: {}", finalMetadata.getTemplates());
    }

    @Test
    public void transformIndexMetadata_AsExpected() throws Exception {
        TestResources.Snapshot snapshot = TestResources.SNAPSHOT_ES_7_10_BWC_CHECK;
        Version version = Version.fromString("ES 7.10");

        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        final var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(version, repo, false);

        CanonicalTransformer transformer = new CanonicalTransformer(2);

        IndexMetadata indexMetadataBwc = sourceResourceProvider.getIndexMetadata()
            .fromRepo(snapshot.name, "bwc_index_1");
        IndexMetadata transformedIndexBwc = transformer.transformIndexMetadata(indexMetadataBwc).get(0);
        IndexMetadataData_OS_2_11 finalIndexBwc = new IndexMetadataData_OS_2_11(
            transformedIndexBwc.getRawJson(), transformedIndexBwc.getId(), transformedIndexBwc.getName()
        );

        assertNotNull(finalIndexBwc.getMappings(), "Mappings should not be null");
        assertNotNull(finalIndexBwc.getSettings(), "Settings should not be null");
        assertEquals("bwc_index_1", finalIndexBwc.getName());
        log.info("Transformed bwc_index_1: {}", finalIndexBwc.getRawJson());
    }
}
