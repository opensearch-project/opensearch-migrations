package org.opensearch.migrations.bulkload.version_es_5_3;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_5_3 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_5_3 repoData = null;

    public SnapshotRepoProvider_ES_5_3(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_5_3 getRepoData() {
        if (repoData == null) {
            this.repoData = SnapshotRepoData_ES_5_3.fromRepo(repo);
        }
        return repoData;
    }

    public List<SnapshotRepoData_ES_5_3.Index> getIndices() {
        return getRepoData().getIndices().entrySet()
                .stream()
                .map(entry -> SnapshotRepoData_ES_5_3.Index.fromRawIndex(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        List<SnapshotRepo.Index> matchedIndices = new ArrayList<>();
        SnapshotRepoData_ES_5_3.Snapshot targetSnapshot = getRepoData().getSnapshots().stream()
                .filter(snapshot -> snapshotName.equals(snapshot.getName()))
                .findFirst()
                .orElse(null);

        if (targetSnapshot != null) {
            getRepoData().getIndices().forEach((indexName, rawIndex) -> {
                List<String> snapshotNames = rawIndex.getSnapshots();
                if (snapshotNames == null || snapshotNames.isEmpty()) {
                    System.err.printf("Skipping index [%s] — no snapshots listed%n", indexName);
                } else if (!snapshotNames.contains(targetSnapshot.getName())) {
                    System.err.printf("Skipping index [%s] — snapshot ID [%s] not found in %s%n",
                            indexName, targetSnapshot.getId(), snapshotNames);
                } else {
                    System.err.printf("Matched index [%s] — snapshot ID [%s] found%n", indexName, targetSnapshot.getName());
                    matchedIndices.add(SnapshotRepoData_ES_5_3.Index.fromRawIndex(indexName, rawIndex));
                }
            });
        } else {
            System.err.printf("No snapshot found with name [%s]%n", snapshotName);
        }
        return matchedIndices;
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        return new ArrayList<>(getRepoData().getSnapshots());
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        for (SnapshotRepoData_ES_5_3.Snapshot snapshot : getRepoData().getSnapshots()) {
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
}
