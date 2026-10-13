package org.opensearch.migrations.bulkload.version_es_1_7;

import java.nio.file.Files;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SourceRepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotRepoProvider_ES_1_7Test {

    @Test
    void keepsOnlyMostRecentlyRequestedSnapshot(@TempDir java.nio.file.Path tempDir) throws Exception {
        var snapshotFile = tempDir.resolve("snap-snapshot");
        Files.writeString(snapshotFile, """
            {
              "snapshot": {
                "indices": ["index-one", "index-two"]
              }
            }
            """);
        var otherSnapshotFile = tempDir.resolve("snap-other");
        Files.writeString(otherSnapshotFile, """
            {
              "snapshot": {
                "indices": ["other-index"]
              }
            }
            """);
        var repo = mock(SourceRepo.class);
        when(repo.getSnapshotMetadataFilePath("snapshot")).thenReturn(snapshotFile);
        when(repo.getSnapshotMetadataFilePath("other")).thenReturn(otherSnapshotFile);
        var provider = new SnapshotRepoProvider_ES_1_7(repo);

        var first = provider.getIndicesInSnapshot("snapshot");
        var second = provider.getIndicesInSnapshot("snapshot");
        provider.getIndicesInSnapshot("other");
        var reloaded = provider.getIndicesInSnapshot("snapshot");

        assertEquals(List.of("index-one", "index-two"), first.stream().map(i -> i.getName()).toList());
        assertSame(first, second);
        assertNotSame(first, reloaded);
        verify(repo, times(2)).getSnapshotMetadataFilePath("snapshot");
        verify(repo).getSnapshotMetadataFilePath("other");
    }
}
