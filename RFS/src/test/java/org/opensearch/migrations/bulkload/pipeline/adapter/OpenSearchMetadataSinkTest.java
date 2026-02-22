package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenSearchMetadataSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private OpenSearchClient client;

    private OpenSearchMetadataSink sink;

    @BeforeEach
    void setUp() {
        sink = new OpenSearchMetadataSink(client);
    }

    @Test
    void writeGlobalMetadataCreatesTemplates() {
        ObjectNode legacyTemplates = MAPPER.createObjectNode();
        legacyTemplates.set("tmpl-1", MAPPER.createObjectNode().put("order", 0));

        ObjectNode indexTemplates = MAPPER.createObjectNode();
        indexTemplates.set("it-1", MAPPER.createObjectNode());

        ObjectNode componentTemplates = MAPPER.createObjectNode();
        componentTemplates.set("ct-1", MAPPER.createObjectNode());

        var metadata = new GlobalMetadataSnapshot(legacyTemplates, indexTemplates, componentTemplates, List.of());

        when(client.createLegacyTemplate(anyString(), any(), isNull()))
            .thenReturn(java.util.Optional.of(MAPPER.createObjectNode()));
        when(client.createIndexTemplate(anyString(), any(), isNull()))
            .thenReturn(java.util.Optional.of(MAPPER.createObjectNode()));
        when(client.createComponentTemplate(anyString(), any(), isNull()))
            .thenReturn(java.util.Optional.of(MAPPER.createObjectNode()));

        StepVerifier.create(sink.writeGlobalMetadata(metadata))
            .verifyComplete();

        verify(client).createLegacyTemplate(eq("tmpl-1"), any(), isNull());
        verify(client).createIndexTemplate(eq("it-1"), any(), isNull());
        verify(client).createComponentTemplate(eq("ct-1"), any(), isNull());
    }

    @Test
    void writeGlobalMetadataHandlesNullTemplates() {
        var metadata = new GlobalMetadataSnapshot(null, null, null, List.of());

        StepVerifier.create(sink.writeGlobalMetadata(metadata))
            .verifyComplete();

        verifyNoInteractions(client);
    }

    @Test
    void createIndexCallsClient() {
        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode settings = MAPPER.createObjectNode();
        var metadata = new IndexMetadataSnapshot("my-index", 1, 0, mappings, settings, null);

        when(client.createIndex(eq("my-index"), any(ObjectNode.class), isNull()))
            .thenReturn(java.util.Optional.of(MAPPER.createObjectNode()));

        StepVerifier.create(sink.createIndex(metadata))
            .verifyComplete();

        verify(client).createIndex(eq("my-index"), any(ObjectNode.class), isNull());
    }
}
