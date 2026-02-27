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
        GlobalMetadataData_OS_2_11 finalMetadata = new GlobalMetadataData_OS_2_11(transformedGlobalMetadata.toObjectNode());

        String expectedBwcTemplates = "{\"bwc_template\":{\"order\":0,\"index_patterns\":[\"bwc_index*\"],\"settings\":{\"number_of_shards\":\"1\",\"number_of_replicas\":1},\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},\"content\":{\"type\":\"text\"}}},\"aliases\":{\"bwc_alias\":{}}}}";
        String expectedIndexTemplates = "{\"fwc_template\":{\"index_patterns\":[\"fwc_index*\"],\"template\":{\"aliases\":{\"fwc_alias\":{}}},\"composed_of\":[\"fwc_mappings\",\"fwc_settings\"]}}";
        String expectedComponentTemplates = "{\"fwc_settings\":{\"template\":{\"settings\":{\"index\":{\"number_of_shards\":\"1\",\"number_of_replicas\":\"0\"}}}},\"fwc_mappings\":{\"template\":{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},\"content\":{\"type\":\"text\"}}}}}}";

        assertEquals(expectedBwcTemplates, finalMetadata.getTemplates().toString());
        assertEquals(expectedIndexTemplates, finalMetadata.getIndexTemplates().toString());
        assertEquals(expectedComponentTemplates, finalMetadata.getComponentTemplates().toString());
    }

    @Test
    public void transformIndexMetadata_AsExpected() throws Exception {
        TestResources.Snapshot snapshot = TestResources.SNAPSHOT_ES_7_10_BWC_CHECK;
        Version version = Version.fromString("ES 7.10");

        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        final var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(version, repo, false);

        CanonicalTransformer transformer = new CanonicalTransformer(2);

        IndexMetadata indexMetadataBwc = sourceResourceProvider.getIndexMetadata().fromRepo(snapshot.name, "bwc_index_1");
        IndexMetadata transformedIndexBwc = transformer.transformIndexMetadata(indexMetadataBwc).get(0);
        IndexMetadataData_OS_2_11 finalIndexBwc =new IndexMetadataData_OS_2_11(transformedIndexBwc.getRawJson(), transformedIndexBwc.getId(), transformedIndexBwc.getName());

        IndexMetadata indexMetadataFwc = sourceResourceProvider.getIndexMetadata().fromRepo(snapshot.name, "fwc_index_1");
        IndexMetadata transformedIndexFwc = transformer.transformIndexMetadata(indexMetadataFwc).get(0);
        IndexMetadataData_OS_2_11 finalIndexFwc =new IndexMetadataData_OS_2_11(transformedIndexFwc.getRawJson(), transformedIndexFwc.getId(), transformedIndexFwc.getName());

        IndexMetadata indexMetadataNoMappingNoDocs = sourceResourceProvider.getIndexMetadata().fromRepo(snapshot.name, "no_mappings_no_docs");
        IndexMetadata transformedIndexNoMappingNoDocs = transformer.transformIndexMetadata(indexMetadataNoMappingNoDocs).get(0);
        IndexMetadataData_OS_2_11 finalIndexNoMappingNoDocs = new IndexMetadataData_OS_2_11(transformedIndexNoMappingNoDocs.getRawJson(), transformedIndexNoMappingNoDocs.getId(), transformedIndexNoMappingNoDocs.getName());

        IndexMetadata indexMetadataEmptyMappingNoDocs = sourceResourceProvider.getIndexMetadata().fromRepo(snapshot.name, "empty_mappings_no_docs");
        IndexMetadata transformedIndexEmptyMappingNoDocs = transformer.transformIndexMetadata(indexMetadataEmptyMappingNoDocs).get(0);
        IndexMetadataData_OS_2_11 finalIndexEmptyMappingNoDocs = new IndexMetadataData_OS_2_11(transformedIndexEmptyMappingNoDocs.getRawJson(), transformedIndexEmptyMappingNoDocs.getId(), transformedIndexEmptyMappingNoDocs.getName());

        String expectedIndexBwc = "{\"version\":3,\"mapping_version\":1,\"settings_version\":1,\"aliases_version\":1,\"routing_num_shards\":1024,\"state\":\"open\",\"settings\":{\"creation_date\":\"1727459371883\",\"number_of_replicas\":1,\"number_of_shards\":\"1\",\"provided_name\":\"bwc_index_1\",\"uuid\":\"tBmFXxGhTeiDlznQiKfNCA\",\"version\":{\"created\":\"7100299\"}},\"mappings\":{\"properties\":{\"content\":{\"type\":\"text\"},\"title\":{\"type\":\"text\"}}},\"aliases\":{\"bwc_alias\":{}},\"primary_terms\":[1],\"in_sync_allocations\":{\"0\":[\"jSYEePXYTka3EJ0vvdPGJA\"]},\"rollover_info\":{},\"system\":false}";
        String expectedIndexFwc = "{\"version\":3,\"mapping_version\":1,\"settings_version\":1,\"aliases_version\":1,\"routing_num_shards\":1024,\"state\":\"open\",\"settings\":{\"creation_date\":\"1727459372123\",\"number_of_replicas\":1,\"number_of_shards\":\"1\",\"provided_name\":\"fwc_index_1\",\"uuid\":\"BI3xMNgnRe6HldftNpfxzQ\",\"version\":{\"created\":\"7100299\"}},\"mappings\":{\"properties\":{\"content\":{\"type\":\"text\"},\"title\":{\"type\":\"text\"}}},\"aliases\":{\"fwc_alias\":{}},\"primary_terms\":[1],\"in_sync_allocations\":{\"0\":[\"SvHX-4xPTZWt0sYMTiFGYw\"]},\"rollover_info\":{},\"system\":false}";
        String expectedIndexNoMappingNoDocs = "{\"version\":3,\"mapping_version\":1,\"settings_version\":1,\"aliases_version\":1,\"routing_num_shards\":1024,\"state\":\"open\",\"settings\":{\"creation_date\":\"1727459372205\",\"number_of_replicas\":1,\"number_of_shards\":\"1\",\"provided_name\":\"no_mappings_no_docs\",\"uuid\":\"S0lxKX7vSLS7jzquAnNzdg\",\"version\":{\"created\":\"7100299\"}},\"mappings\":{},\"aliases\":{},\"primary_terms\":[1],\"in_sync_allocations\":{\"0\":[\"McFl7a21QZKeB7HLHo6eMQ\"]},\"rollover_info\":{},\"system\":false}";
        String expectedIndexEmptyMappingNoDocs = "{\"version\":3,\"mapping_version\":1,\"settings_version\":1,\"aliases_version\":1,\"routing_num_shards\":1024,\"state\":\"open\",\"settings\":{\"creation_date\":\"1727459372264\",\"number_of_replicas\":1,\"number_of_shards\":\"1\",\"provided_name\":\"empty_mappings_no_docs\",\"uuid\":\"ndSIWVDSQpuC8xY2DieX7g\",\"version\":{\"created\":\"7100299\"}},\"mappings\":{},\"aliases\":{},\"primary_terms\":[1],\"in_sync_allocations\":{\"0\":[\"yr1xkacYT42MjPGjAzQhHg\"]},\"rollover_info\":{},\"system\":false}";

        assertEquals(expectedIndexBwc, finalIndexBwc.getRawJson().toString());
        assertEquals(expectedIndexFwc, finalIndexFwc.getRawJson().toString());
        assertEquals(expectedIndexNoMappingNoDocs, finalIndexNoMappingNoDocs.getRawJson().toString());
        assertEquals(expectedIndexEmptyMappingNoDocs, finalIndexEmptyMappingNoDocs.getRawJson().toString());
    }
}
