package org.opensearch.migrations.bulkload.worker;

import java.util.List;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.IndexCreator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexRunnerTest {

    @Test
    void readsOnlySelectedIndexMetadataOnceAndAggregatesSkippedIndexes() {
        var selected = index("selected");
        var skippedOne = index("skipped-1");
        var skippedTwo = index("skipped-2");
        var provider = mock(SnapshotRepo.Provider.class);
        doReturn(List.of(skippedOne, selected, skippedTwo)).when(provider).getIndicesInSnapshot("snapshot");

        var aliases = new ObjectMapper().createObjectNode();
        aliases.putObject("selected-alias");
        var originalMetadata = mock(IndexMetadata.class);
        var copiedMetadata = mock(IndexMetadata.class);
        when(originalMetadata.getAliases()).thenReturn(aliases);
        when(originalMetadata.deepCopy()).thenReturn(copiedMetadata);

        var metadataFactory = mock(IndexMetadata.Factory.class);
        when(metadataFactory.getRepoDataProvider()).thenReturn(provider);
        when(metadataFactory.fromRepo("snapshot", "selected")).thenReturn(originalMetadata);

        var transformer = mock(Transformer.class);
        when(transformer.transformIndexMetadata(copiedMetadata)).thenReturn(List.of(copiedMetadata));
        var indexCreator = mock(IndexCreator.class);
        var awarenessAttributeSettings = new AwarenessAttributeSettings(false, 0);
        when(indexCreator.create(
            copiedMetadata,
            MigrationMode.SIMULATE,
            awarenessAttributeSettings,
            null
        )).thenReturn(CreationResult.builder().name("selected").build());

        var results = new IndexRunner(
            "snapshot",
            metadataFactory,
            indexCreator,
            transformer,
            List.of("selected"),
            awarenessAttributeSettings
        ).migrateIndices(MigrationMode.SIMULATE, null);

        assertEquals(1, results.getIndexes().size());
        assertEquals("selected", results.getIndexes().get(0).getName());
        assertEquals(1, results.getAliases().size());
        assertEquals("selected-alias", results.getAliases().get(0).getName());
        assertEquals(2, results.getSkippedIndexes());
        verify(metadataFactory).fromRepo("snapshot", "selected");
        verify(metadataFactory, never()).fromRepo("snapshot", "skipped-1");
        verify(metadataFactory, never()).fromRepo("snapshot", "skipped-2");
    }

    private static SnapshotRepo.Index index(String name) {
        var index = mock(SnapshotRepo.Index.class);
        when(index.getName()).thenReturn(name);
        return index;
    }
}
