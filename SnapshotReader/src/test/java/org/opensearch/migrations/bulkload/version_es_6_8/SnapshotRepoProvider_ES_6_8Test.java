package org.opensearch.migrations.bulkload.version_es_6_8;

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

class SnapshotRepoProvider_ES_6_8Test {

    @Test
    void keepsOnlyMostRecentlyRequestedSnapshot() {
        var repoData = mock(SnapshotRepoData_ES_6_8.class);
        var targetSnapshot = SnapshotRepoData_ES_6_8.Snapshot.builder()
            .name("snapshot")
            .id("snapshot-id")
            .build();
        var otherSnapshot = SnapshotRepoData_ES_6_8.Snapshot.builder()
            .name("other")
            .id("other-snapshot-id")
            .build();
        when(repoData.getSnapshots()).thenReturn(List.of(targetSnapshot, otherSnapshot));

        Map<String, SnapshotRepoData_ES_6_8.RawIndex> repositoryIndices = new LinkedHashMap<>();
        repositoryIndices.put(
            "index-by-id",
            new SnapshotRepoData_ES_6_8.RawIndex(
                "index-id-1",
                List.of(SnapshotRepoData_ES_6_8.Snapshot.builder().id("snapshot-id").build())
            )
        );
        repositoryIndices.put(
            "index-by-name",
            new SnapshotRepoData_ES_6_8.RawIndex(
                "index-id-2",
                List.of(SnapshotRepoData_ES_6_8.Snapshot.builder().name("snapshot").build())
            )
        );
        repositoryIndices.put(
            "other-index",
            new SnapshotRepoData_ES_6_8.RawIndex(
                "index-id-3",
                List.of(SnapshotRepoData_ES_6_8.Snapshot.builder().id("other-snapshot-id").build())
            )
        );
        when(repoData.getIndices()).thenReturn(repositoryIndices);

        var provider = new TestProvider(repoData);
        var first = provider.getIndicesInSnapshot("snapshot");
        var second = provider.getIndicesInSnapshot("snapshot");
        provider.getIndicesInSnapshot("other");
        var reloaded = provider.getIndicesInSnapshot("snapshot");

        assertEquals(List.of("index-by-id", "index-by-name"), first.stream().map(i -> i.getName()).sorted().toList());
        assertSame(first, second);
        assertNotSame(first, reloaded);
        verify(repoData, times(3)).getIndices();
    }

    private static class TestProvider extends SnapshotRepoProvider_ES_6_8 {
        private final SnapshotRepoData_ES_6_8 repoData;

        TestProvider(SnapshotRepoData_ES_6_8 repoData) {
            super(mock(SourceRepo.class));
            this.repoData = repoData;
        }

        @Override
        protected SnapshotRepoData_ES_6_8 getRepoData() {
            return repoData;
        }
    }
}
