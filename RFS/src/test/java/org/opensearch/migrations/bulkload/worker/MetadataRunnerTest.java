package org.opensearch.migrations.bulkload.worker;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataRunnerTest {

    @Test
    void appliesTemplateSelectionBeforeTransformationAndReportsSkippedItems() {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.putObject("templates").putObject("legacy-keep");
        root.withObject("/templates").putObject("legacy-skip");
        root.putObject("index_template").putObject("index_template").putObject("index-keep");
        root.withObject("/index_template/index_template").putObject("index-skip");
        root.putObject("component_template").putObject("component_template").putObject("component-keep");
        root.withObject("/component_template/component_template").putObject("component-skip");
        var metadata = new GlobalMetadataData_OS_2_11(root);

        var factory = mock(GlobalMetadata.Factory.class);
        when(factory.fromRepo("snapshot")).thenReturn(metadata);

        var client = mock(OpenSearchClient.class);
        var creator = new GlobalMetadataCreator_OS_2_11(
            client,
            List.of("legacy-keep"),
            List.of("component-keep"),
            List.of("index-keep")
        );
        var transformer = mock(Transformer.class);
        when(transformer.transformGlobalMetadata(any())).thenAnswer(invocation -> {
            var selected = invocation.<GlobalMetadata>getArgument(0);
            assertEquals(List.of("legacy-keep"), fieldNames(selected.getTemplates()));
            assertEquals(List.of("index-keep"), fieldNames(selected.getIndexTemplates()));
            assertEquals(List.of("component-keep"), fieldNames(selected.getComponentTemplates()));
            return selected;
        });
        doReturn(false).when(client).hasLegacyTemplate(anyString());
        doReturn(false).when(client).hasIndexTemplate(anyString());
        doReturn(false).when(client).hasComponentTemplate(anyString());

        var runner = new MetadataRunner("snapshot", factory, creator, transformer);
        var selection = runner.selectTemplates();
        var results = runner.migrateMetadata(
            MigrationMode.SIMULATE,
            mock(IClusterMetadataContext.class),
            selection
        );

        verify(factory).fromRepo("snapshot");
        verify(transformer).transformGlobalMetadata(metadata);
        assertEquals(2, results.getLegacyTemplates().size());
        assertEquals(2, results.getIndexTemplates().size());
        assertEquals(2, results.getComponentTemplates().size());
        assertEquals(3, java.util.stream.Stream.of(
                results.getLegacyTemplates(),
                results.getIndexTemplates(),
                results.getComponentTemplates())
            .flatMap(List::stream)
            .filter(r -> r.getFailureType() == CreationFailureType.SKIPPED_DUE_TO_FILTER)
            .count());
        verify(client).hasLegacyTemplate("legacy-keep");
        verify(client).hasIndexTemplate("index-keep");
        verify(client).hasComponentTemplate("component-keep");
        verify(client, never()).hasLegacyTemplate("legacy-skip");
        verify(client, never()).hasIndexTemplate("index-skip");
        verify(client, never()).hasComponentTemplate("component-skip");
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.node.ObjectNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
