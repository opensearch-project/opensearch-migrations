package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotMetadataSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SNAPSHOT = "test-snap";

    @Mock private SnapshotExtractor extractor;
    @Mock private ClusterSnapshotReader snapshotReader;
    @Mock private GlobalMetadata.Factory globalMetadataFactory;
    @Mock private IndexMetadata.Factory indexMetadataFactory;
    @Mock private GlobalMetadata globalMetadata;
    @Mock private IndexMetadata indexMetadata;

    private SnapshotMetadataSource source;

    @BeforeEach
    void setUp() {
        source = new SnapshotMetadataSource(extractor, SNAPSHOT);
    }

    @Test
    void readGlobalMetadataConvertsFromExisting() {
        ObjectNode templates = MAPPER.createObjectNode().put("t1", "v1");
        ObjectNode indexTemplates = MAPPER.createObjectNode();
        ObjectNode componentTemplates = MAPPER.createObjectNode();

        when(extractor.getSnapshotReader()).thenReturn(snapshotReader);
        when(snapshotReader.getGlobalMetadata()).thenReturn(globalMetadataFactory);
        when(globalMetadataFactory.fromRepo(SNAPSHOT)).thenReturn(globalMetadata);
        when(globalMetadata.getTemplates()).thenReturn(templates);
        when(globalMetadata.getIndexTemplates()).thenReturn(indexTemplates);
        when(globalMetadata.getComponentTemplates()).thenReturn(componentTemplates);
        when(extractor.listIndices(SNAPSHOT)).thenReturn(List.of("idx-a", "idx-b"));

        var result = source.readGlobalMetadata();

        assertEquals(templates, result.templates());
        assertEquals(List.of("idx-a", "idx-b"), result.indices());
    }

    @Test
    void readIndexMetadataConvertsFromExisting() {
        ObjectNode mappings = MAPPER.createObjectNode().put("type", "keyword");
        ObjectNode settings = MAPPER.createObjectNode().put("number_of_replicas", 1);
        ObjectNode aliases = MAPPER.createObjectNode();

        when(extractor.getSnapshotReader()).thenReturn(snapshotReader);
        when(snapshotReader.getIndexMetadata()).thenReturn(indexMetadataFactory);
        when(indexMetadataFactory.fromRepo(SNAPSHOT, "my-index")).thenReturn(indexMetadata);
        when(indexMetadata.getNumberOfShards()).thenReturn(5);
        when(indexMetadata.getMappings()).thenReturn(mappings);
        when(indexMetadata.getSettings()).thenReturn(settings);
        when(indexMetadata.getAliases()).thenReturn(aliases);

        var result = source.readIndexMetadata("my-index");

        assertEquals("my-index", result.indexName());
        assertEquals(5, result.numberOfShards());
        assertEquals(1, result.numberOfReplicas());
    }
}
