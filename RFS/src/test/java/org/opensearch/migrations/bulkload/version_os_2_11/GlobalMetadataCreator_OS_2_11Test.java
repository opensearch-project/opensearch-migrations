package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.opensearch.migrations.metadata.CreationResult.CreationFailureType.SKIPPED_DUE_TO_FILTER;
import static org.opensearch.migrations.metadata.CreationResult.CreationFailureType.SKIPPED_ON_COLLECTION_ROUTED_TARGET;

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
        doReturn(filledOptional).when(client).createComponentTemplate(any(), any(), any());
        doReturn(filledOptional).when(client).createIndexTemplate(any(), any(), any());
        doReturn(filledOptional).when(client).createLegacyTemplate(any(), any(), any());

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

    @ParameterizedTest
    @EnumSource(MigrationMode.class)
    void testCreate_collectionRoutedTarget_skipsTemplates(MigrationMode mode) {
        doReturn(true).when(client).isCollectionRouted();
        var mapper = new ObjectMapper();
        var obj = mapper.createObjectNode();

        var globalMetadata = mock(GlobalMetadata.class);
        var indexTemplates = mapper.createObjectNode().put("type", "index");
        doReturn(indexTemplates).when(globalMetadata).getIndexTemplates();

        var creator = spy(new GlobalMetadataCreator_OS_2_11(client, List.of(), List.of(), List.of("it1")));
        doReturn(Map.of("it1", obj, ".its", obj)).when(creator).getAllTemplates(indexTemplates);

        var results = creator.create(globalMetadata, mode, context);

        assertThat(results.fatalIssueCount(), equalTo(0L));
        assertThat(results.getIndexTemplates(), containsInAnyOrder(
            createResult("it1", SKIPPED_ON_COLLECTION_ROUTED_TARGET),
            createResult(".its", SKIPPED_DUE_TO_FILTER)));
        verify(client, never()).createIndexTemplate(any(), any(), any());
        verify(client, never()).hasIndexTemplate(any());
    }

    @Test
    void testCreate_clientBuiltForCollectionRoutedTarget_neverContactsTheTarget() {
        // Nothing listens on this port, so any request would surface as a TARGET_CLUSTER_FAILURE
        var routedClient = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
            .host("https://localhost:1")
            .awsRegion("us-east-1")
            .awsServiceSigningName("aoss")
            .collectionRouted(true)
            .build()
            .toConnectionContext()).determineVersionAndCreate();
        var mapper = new ObjectMapper();
        var globalMetadata = mock(GlobalMetadata.class);
        doReturn(mapper.createObjectNode().set("it1", mapper.createObjectNode()))
            .when(globalMetadata).getIndexTemplates();

        var results = new GlobalMetadataCreator_OS_2_11(routedClient, List.of(), List.of(), List.of("it1"))
            .create(globalMetadata, MigrationMode.PERFORM, context);

        assertThat(results.getIndexTemplates(), containsInAnyOrder(
            createResult("it1", SKIPPED_ON_COLLECTION_ROUTED_TARGET)));
    }

    private CreationResult createSuccessResult(String name) {
        return createResult(name, null);
    }

    private CreationResult createResult(String name, CreationFailureType type) {
        return CreationResult.builder().name(name).failureType(type).build();
    }
}
