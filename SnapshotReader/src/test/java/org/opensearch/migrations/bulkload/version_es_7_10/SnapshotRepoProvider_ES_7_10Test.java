package org.opensearch.migrations.bulkload.version_es_7_10;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.SourceRepo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotRepoProvider_ES_7_10Test {

    @Test
    void keepsOnlyMostRecentlyRequestedSnapshot() {
        var repoData = mock(SnapshotRepoData_ES_7_10.class);
        Map<String, SnapshotRepoData_ES_7_10.RawIndex> repositoryIndices = new LinkedHashMap<>();
        repositoryIndices.put(
            "index-one",
            new SnapshotRepoData_ES_7_10.RawIndex("id-1", List.of("snapshot-id"), List.of())
        );
        repositoryIndices.put(
            "index-two",
            new SnapshotRepoData_ES_7_10.RawIndex("id-2", List.of("snapshot-id"), List.of())
        );
        when(repoData.getIndices()).thenReturn(repositoryIndices);

        Map<String, String> snapshotIndices = new LinkedHashMap<>();
        snapshotIndices.put("id-2", "metadata-2");
        snapshotIndices.put("id-1", "metadata-1");
        when(repoData.getSnapshots()).thenReturn(List.of(
            new SnapshotRepoData_ES_7_10.Snapshot("snapshot", "snapshot-id", 1, snapshotIndices, "7.10.2"),
            new SnapshotRepoData_ES_7_10.Snapshot("other", "other-id", 1, Map.of("id-1", "metadata-1"), "7.10.2")
        ));

        var provider = new TestProvider(repoData);
        var first = provider.getIndicesInSnapshot("snapshot");
        var second = provider.getIndicesInSnapshot("snapshot");
        provider.getIndicesInSnapshot("other");
        var reloaded = provider.getIndicesInSnapshot("snapshot");

        assertEquals(List.of("index-two", "index-one"), first.stream().map(i -> i.getName()).toList());
        assertSame(first, second);
        assertNotSame(first, reloaded);
        verify(repoData, times(1)).getIndices();
    }

    private static class TestProvider extends SnapshotRepoProvider_ES_7_10 {
        private final SnapshotRepoData_ES_7_10 repoData;

        TestProvider(SnapshotRepoData_ES_7_10 repoData) {
            super(mock(SourceRepo.class));
            this.repoData = repoData;
        }

        @Override
        protected SnapshotRepoData_ES_7_10 getRepoData() {
            return repoData;
        }
    }
}
