package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataCreator_OS_2_11.TemplateTypes;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
        doReturn(filledOptional).when(client).createComponentTemplate(any(), any(), any());
        doReturn(filledOptional).when(client).createIndexTemplate(any(), any(), any());
        doReturn(filledOptional).when(client).createLegacyTemplate(any(), any(), any());

        var creator = spy(new GlobalMetadataCreator_OS_2_11(client, List.of("lit1"), List.of(), null));
        doReturn(Map.of("lit1", obj, "lit2", obj, ".lits", obj)).when(creator).getAllTemplates(any(), eq(TemplateTypes.LEGACY_INDEX_TEMPLATE));
        doReturn(Map.of("it1", obj, ".its", obj)).when(creator).getAllTemplates(any(), eq(TemplateTypes.INDEX_TEMPLATE));
        doReturn(Map.of("ct1", obj, ".cts", obj)).when(creator).getAllTemplates(any(), eq(TemplateTypes.COMPONENT_TEMPLATE));

        var globalMetadata = mock(GlobalMetadata.class);
        doReturn(obj).when(globalMetadata).getComponentTemplates();
        doReturn(obj).when(globalMetadata).getIndexTemplates();
        doReturn(obj).when(globalMetadata).getTemplates();

        var results = creator.create(globalMetadata, MigrationMode.PERFORM, context);
        assertThat(results.fatalIssueCount(), equalTo(0L));
        assertThat(results.getLegacyTemplates(), containsInAnyOrder(createSuccessResult("lit1"), createResult("lit2", SKIPPED_DUE_TO_FILTER), createResult(".lits", SKIPPED_DUE_TO_FILTER)));
        assertThat(results.getComponentTemplates(), containsInAnyOrder(createResult("ct1", SKIPPED_DUE_TO_FILTER), createSuccessResult(".cts")));
        assertThat(results.getIndexTemplates(), containsInAnyOrder(createResult("it1", SKIPPED_DUE_TO_FILTER), createSuccessResult(".its")));
    }

    private CreationResult createSuccessResult(String name) {
        return createResult(name, null);
    }

    private CreationResult createResult(String name, CreationFailureType type) {
        return CreationResult.builder().name(name).failureType(type).build();
    }
}
