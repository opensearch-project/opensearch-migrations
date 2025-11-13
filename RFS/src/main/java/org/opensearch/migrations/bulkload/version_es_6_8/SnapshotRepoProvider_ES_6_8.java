package org.opensearch.migrations.bulkload.version_es_6_8;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_6_8 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_6_8 repoData = null;

    public SnapshotRepoProvider_ES_6_8(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_6_8 getRepoData() {
        if (repoData == null) {
            this.repoData = SnapshotRepoData_ES_6_8.fromRepo(repo);
        }
        return repoData;
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        return new ArrayList<>(getRepoData().getSnapshots());
    }

    @SuppressWarnings({
        "java:S100",   // Naming convention check
        "java:S1186"   // Empty method check
    })
    private record IndexWithSnapshots(
        String name,
        String id,
        List<? extends SnapshotRepo.Snapshot> snapshots
    ) { }

    /**
     * Retrieves all indices that belong to the specified snapshot.
     * Uses stream operations to: 
     * - find the target snapshot by name,
     * - filter repo indices to those containing that snapshot (by name or UUID),
     * - return the matching indices.
     */
    @Override
    public List<? extends SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        return getSnapshotForName(snapshotName)
            .map(matchedSnapshot ->
                    getRepoData().getIndices()
                    .entrySet()
                    .stream().map(keyVal -> new IndexWithSnapshots(
                                    keyVal.getKey(),
                                    keyVal.getValue().getId(),
                                    keyVal.getValue().getSnapshots())
                    )
                    .filter(
                        index -> index.snapshots().stream()
                                .anyMatch(matchedSnapshot::isNameOrIdEqual)
                    )
                    .map(index -> new SnapshotRepoData_ES_6_8.Index(index.name(), index.id()))
                    .toList()
            )
            .orElse(List.of());
    }

    private Optional<? extends SnapshotRepo.Snapshot> getSnapshotForName(String snapshotName) {
        return getRepoData().getSnapshots().stream()
                .filter(snapshot -> snapshotName.equals(snapshot.getName()))
                .findFirst();
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        for (SnapshotRepoData_ES_6_8.Snapshot snapshot : getRepoData().getSnapshots()) {
            if (snapshot.getName().equals(snapshotName)) {
                return snapshot.getId();
            }
        }
        return null;
    }

    @Override
    public String getIndexId(String indexName) {
        var rawIndex = getRepoData().getIndices().get(indexName);
        return rawIndex != null ? rawIndex.getId() : null;
    }

    @Override
    public SourceRepo getRepo() {
        return repo;
    }

}
