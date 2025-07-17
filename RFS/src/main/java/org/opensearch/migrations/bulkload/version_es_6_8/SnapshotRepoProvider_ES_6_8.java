package org.opensearch.migrations.bulkload.version_es_6_8;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        List<SnapshotRepo.Index> matchedIndices = new ArrayList<>();
        SnapshotRepoData_ES_6_8.Snapshot targetSnapshot = getRepoData().getSnapshots().stream()
            .filter(snapshot -> snapshotName.equals(snapshot.getName()))
            .findFirst()
            .orElse(null);

        if (targetSnapshot != null) {
            getRepoData().getIndices().forEach((indexName, rawIndex) -> {
                if (rawIndex.getSnapshots().contains(targetSnapshot.getId())) {
                    matchedIndices.add(SnapshotRepoData_ES_6_8.Index.fromRawIndex(indexName, rawIndex));
                }
            });
        }
        return matchedIndices;
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
        return getRepoData().getIndices().get(indexName).getId();
    }

    @Override
    public SourceRepo getRepo() {
        return repo;
    }

    public List<SnapshotRepoData_ES_6_8.Index> getIndices() {
        return getRepoData().getIndices().entrySet()
            .stream()
            .map(entry -> SnapshotRepoData_ES_6_8.Index.fromRawIndex(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    }
}
