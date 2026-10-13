package org.opensearch.migrations.bulkload.version_es_6_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_6_8 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_6_8 repoData = null;
    private final Map<String, List<SnapshotRepo.Index>> indicesBySnapshotName = new HashMap<>();

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

    /**
     * Retrieves all indices that belong to the specified snapshot.
     * Scans the repository index list once, then caches the result for each requested snapshot.
     */
    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        return indicesBySnapshotName.computeIfAbsent(snapshotName, this::loadIndicesInSnapshot);
    }

    private List<SnapshotRepo.Index> loadIndicesInSnapshot(String snapshotName) {
        return getSnapshotForName(snapshotName)
            .map(snapshot -> getRepoData().getIndices().entrySet().stream()
                .filter(entry -> entry.getValue().getSnapshots().stream().anyMatch(snapshot::isNameOrIdEqual))
                .<SnapshotRepo.Index>map(
                    entry -> new SnapshotRepoData_ES_6_8.Index(entry.getKey(), entry.getValue().getId())
                )
                .toList())
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
