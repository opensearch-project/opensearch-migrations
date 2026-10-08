package org.opensearch.migrations.bulkload.worker;

import java.util.List;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.ServerlessCollectionRouting;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexRunnerCollectionRoutingTest {

    @Test
    void routesBySourceIndexName_evenWhenTransformerRenamesTheIndex() {
        var sourceIndex = mock(SnapshotRepo.Index.class);
        when(sourceIndex.getName()).thenReturn("tenant-a-2024");
        var repoDataProvider = mock(SnapshotRepo.Provider.class);
        doReturn(List.of(sourceIndex)).when(repoDataProvider).getIndicesInSnapshot("snap");

        var sourceMetadata = mock(IndexMetadata.class);
        when(sourceMetadata.deepCopy()).thenReturn(sourceMetadata);
        when(sourceMetadata.getAliases()).thenReturn(new ObjectMapper().createObjectNode());
        var metadataFactory = mock(IndexMetadata.Factory.class);
        when(metadataFactory.getRepoDataProvider()).thenReturn(repoDataProvider);
        when(metadataFactory.fromRepo("snap", "tenant-a-2024")).thenReturn(sourceMetadata);

        var renamedMetadata = mock(IndexMetadata.class);
        var transformer = mock(Transformer.class);
        when(transformer.transformIndexMetadata(sourceMetadata)).thenReturn(List.of(renamedMetadata));

        var indexCreator = mock(IndexCreator.class);
        when(indexCreator.create(any(), any(), any(), any(), any()))
            .thenReturn(CreationResult.builder().name("renamed").build());

        var routing = ServerlessCollectionRouting.fromJson(
            "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(.+)-\\\\d{4}\", \"collection\": \"$1\"}]}").orElseThrow();
        var awareness = new AwarenessAttributeSettings(false, 0);
        var runner = new IndexRunner("snap", metadataFactory, indexCreator, transformer, List.of(), awareness, routing);

        var context = mock(ICreateIndexContext.class);
        runner.migrateIndices(MigrationMode.PERFORM, context);

        verify(indexCreator).create(eq(renamedMetadata), eq(MigrationMode.PERFORM), eq(awareness), eq(context), eq("tenant-a"));
    }
}
