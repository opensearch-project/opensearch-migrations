package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.opensearch.migrations.metadata.CreationResult.CreationFailureType.SKIPPED_DUE_TO_FILTER;

@ExtendWith(MockitoExtension.class)
public class GlobalMetadataCreator_OS_2_11Test {

    @Mock
    OpenSearchClient client;

    @Mock
    IClusterMetadataContext context;

    @Test
    void testCreate() {
        var mapper = new ObjectMapper();
        var obj = mapper.createObjectNode();
        var filledOptional = Optional.of(obj);
        doReturn(filledOptional).when(client).createComponentTemplate(any(), any(), any(), any());
        doReturn(filledOptional).when(client).createIndexTemplate(any(), any(), any(), any());
        doReturn(filledOptional).when(client).createLegacyTemplate(any(), any(), any(), any());

        var globalMetadata = mock(GlobalMetadata.class);
        var componentTemplates = mapper.createObjectNode().put("type", "component");
        var indexTemplates = mapper.createObjectNode().put("type", "index");
        var legacyTemplates = mapper.createObjectNode().put("type", "legacy");
        doReturn(componentTemplates).when(globalMetadata).getComponentTemplates();
        doReturn(indexTemplates).when(globalMetadata).getIndexTemplates();
        doReturn(legacyTemplates).when(globalMetadata).getTemplates();

        var creator = spy(new GlobalMetadataCreator_OS_2_11(client, List.of("lit1"), List.of(), null));
        doReturn(Map.of("lit1", obj, "lit2", obj, ".lits", obj)).when(creator).getAllTemplates(legacyTemplates);
        doReturn(Map.of("it1", obj, ".its", obj)).when(creator).getAllTemplates(indexTemplates);
        doReturn(Map.of("ct1", obj, ".cts", obj)).when(creator).getAllTemplates(componentTemplates);

        var results = creator.create(globalMetadata, MigrationMode.PERFORM, context);
        assertThat(results.fatalIssueCount(), equalTo(0L));
        assertThat(results.getLegacyTemplates(), containsInAnyOrder(createSuccessResult("lit1"), createResult("lit2", SKIPPED_DUE_TO_FILTER), createResult(".lits", SKIPPED_DUE_TO_FILTER)));
        assertThat(results.getComponentTemplates(), containsInAnyOrder(createSuccessResult("ct1"), createResult(".cts", SKIPPED_DUE_TO_FILTER)));
        assertThat(results.getIndexTemplates(), containsInAnyOrder(createSuccessResult("it1"), createResult(".its", SKIPPED_DUE_TO_FILTER)));
    }

    @Test
    void testCreate_collectionRouted_createsEachTemplateInEveryCollection() {
        var mapper = new ObjectMapper();
        var obj = mapper.createObjectNode();
        doReturn(Optional.of(obj)).when(client).createIndexTemplate(any(), any(), any(), any());

        var globalMetadata = mock(GlobalMetadata.class);
        var indexTemplates = mapper.createObjectNode().put("type", "index");
        doReturn(indexTemplates).when(globalMetadata).getIndexTemplates();

        var creator = spy(new GlobalMetadataCreator_OS_2_11(client, List.of(), List.of(), List.of("it1")));
        doReturn(Map.of("it1", obj, ".its", obj)).when(creator).getAllTemplates(indexTemplates);

        var results = creator.create(globalMetadata, MigrationMode.PERFORM, context, List.of("a", "b"));

        assertThat(results.fatalIssueCount(), equalTo(0L));
        assertThat(results.getIndexTemplates(), containsInAnyOrder(
            createSuccessResult("it1 (collection a)"),
            createSuccessResult("it1 (collection b)"),
            createResult(".its", SKIPPED_DUE_TO_FILTER)));
        verify(client).createIndexTemplate(eq("it1"), any(), any(), eq("a"));
        verify(client).createIndexTemplate(eq("it1"), any(), any(), eq("b"));
    }

    @Test
    void testCreate_collectionRoutedSimulate_checksEveryCollection() {
        var mapper = new ObjectMapper();
        var obj = mapper.createObjectNode();
        doReturn(true).when(client).hasComponentTemplate("ct1", "a");
        doReturn(false).when(client).hasComponentTemplate("ct1", "b");

        var globalMetadata = mock(GlobalMetadata.class);
        var componentTemplates = mapper.createObjectNode().put("type", "component");
        doReturn(componentTemplates).when(globalMetadata).getComponentTemplates();

        var creator = spy(new GlobalMetadataCreator_OS_2_11(client, List.of(), List.of("ct1"), List.of()));
        doReturn(Map.of("ct1", obj)).when(creator).getAllTemplates(componentTemplates);

        var results = creator.create(globalMetadata, MigrationMode.SIMULATE, context, List.of("a", "b"));

        assertThat(results.getComponentTemplates(), containsInAnyOrder(
            createResult("ct1 (collection a)", CreationFailureType.METADATA_ALREADY_EXISTS),
            createSuccessResult("ct1 (collection b)")));
    }

    @Test
    void testCreate_defaultGlobalMetadataCreator_rejectsCollections() {
        var expected = GlobalMetadataCreatorResults.builder().build();
        GlobalMetadataCreator plain = (metadata, mode, ctx) -> expected;

        assertThat(plain.create(null, MigrationMode.PERFORM, context, List.of()), equalTo(expected));
        assertThrows(UnsupportedOperationException.class,
            () -> plain.create(null, MigrationMode.PERFORM, context, List.of("a")));
    }

    private CreationResult createSuccessResult(String name) {
        return createResult(name, null);
    }

    private CreationResult createResult(String name, CreationFailureType type) {
        return CreationResult.builder().name(name).failureType(type).build();
    }
}
