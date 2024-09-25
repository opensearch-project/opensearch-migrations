package org.opensearch.migrations.bulkload.transformers;


import org.junit.jupiter.api.Test;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.bulkload.version_os_2_11.IndexMetadataData_OS_2_11;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class Transformer_ES_7_10_OS_2_11Test {
    @Test
    public void transformGlobalMetadata_AsExpected() throws Exception {
        TestResources.Snapshot snapshot = TestResources.SNAPSHOT_ES_7_10_BWC_CHECK;
        Version version = Version.fromString("ES 7.10");

        final var repo = new FileSystemRepo(snapshot.dir);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo);

        Transformer_ES_7_10_OS_2_11 transformer = new Transformer_ES_7_10_OS_2_11(2);

        GlobalMetadata globalMetadata = sourceResourceProvider.getGlobalMetadata().fromRepo(snapshot.name);
        GlobalMetadata transformedGlobalMetadata = transformer.transformGlobalMetadata(globalMetadata);
        GlobalMetadataData_OS_2_11 finalMetadata = new GlobalMetadataData_OS_2_11(transformedGlobalMetadata.toObjectNode());

        String expectedBwcTemplates = "{\"bwc_template\":{\"order\":0,\"index_patterns\":[\"bwc_index*\"],\"settings\":{\"number_of_shards\":\"1\",\"number_of_replicas\":\"0\"},\"mappings\":[{\"arbitrary_type\":{\"properties\":{\"title\":{\"type\":\"text\"},\"content\":{\"type\":\"text\"}}}}],\"aliases\":{\"bwc_alias\":{}}}}";
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

        final var repo = new FileSystemRepo(snapshot.dir);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo);

        Transformer_ES_7_10_OS_2_11 transformer = new Transformer_ES_7_10_OS_2_11(2);

        IndexMetadata indexMetadataBwc = sourceResourceProvider.getIndexMetadata().fromRepo(snapshot.name, "bwc_index_1");
        IndexMetadata transformedIndexBwc = transformer.transformIndexMetadata(indexMetadataBwc);
        IndexMetadataData_OS_2_11 finalIndexBwc =new IndexMetadataData_OS_2_11(transformedIndexBwc.getRawJson(), transformedIndexBwc.getId(), transformedIndexBwc.getName());

        IndexMetadata indexMetadataFwc = sourceResourceProvider.getIndexMetadata().fromRepo(snapshot.name, "fwc_index_1");
        IndexMetadata transformedIndexFwc = transformer.transformIndexMetadata(indexMetadataFwc);
        IndexMetadataData_OS_2_11 finalIndexFwc =new IndexMetadataData_OS_2_11(transformedIndexFwc.getRawJson(), transformedIndexFwc.getId(), transformedIndexFwc.getName());

        String expectedIndexBwc = "{\"version\":3,\"mapping_version\":1,\"settings_version\":1,\"aliases_version\":1,\"routing_num_shards\":1024,\"state\":\"open\",\"settings\":{\"creation_date\":\"1727285787498\",\"number_of_replicas\":1,\"number_of_shards\":\"1\",\"provided_name\":\"bwc_index_1\",\"uuid\":\"P4PDS4fFSECIprHxpKSoiQ\",\"version\":{\"created\":\"7100299\"}},\"mappings\":{\"properties\":{\"content\":{\"type\":\"text\"},\"title\":{\"type\":\"text\"}}},\"aliases\":{\"bwc_alias\":{}},\"primary_terms\":[1],\"in_sync_allocations\":{\"0\":[\"0M-gMXkNQFC02lnWJN4BVQ\"]},\"rollover_info\":{},\"system\":false}";
        String expectedIndexFwc = "{\"version\":3,\"mapping_version\":1,\"settings_version\":1,\"aliases_version\":1,\"routing_num_shards\":1024,\"state\":\"open\",\"settings\":{\"creation_date\":\"1727285787822\",\"number_of_replicas\":1,\"number_of_shards\":\"1\",\"provided_name\":\"fwc_index_1\",\"uuid\":\"riC1gLlrR2eh_uAh9Zdpiw\",\"version\":{\"created\":\"7100299\"}},\"mappings\":{\"properties\":{\"content\":{\"type\":\"text\"},\"title\":{\"type\":\"text\"}}},\"aliases\":{\"fwc_alias\":{}},\"primary_terms\":[1],\"in_sync_allocations\":{\"0\":[\"8sKrVCEaSxy4yP3cx8kcYQ\"]},\"rollover_info\":{},\"system\":false}";

        assertEquals(expectedIndexBwc, finalIndexBwc.getRawJson().toString());
        assertEquals(expectedIndexFwc, finalIndexFwc.getRawJson().toString());
    }
}
