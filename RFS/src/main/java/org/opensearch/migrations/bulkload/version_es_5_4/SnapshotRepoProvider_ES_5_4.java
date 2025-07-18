package org.opensearch.migrations.bulkload.version_es_5_4;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnapshotRepoProvider_ES_5_4 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_5_4 repoData;

    public SnapshotRepoProvider_ES_5_4(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_5_4 getRepoData() {
        if (repoData == null) {
            repoData = SnapshotRepoData_ES_5_4.fromRepo(repo);
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
        var targetSnapshot = getRepoData().getSnapshots().stream()
                .filter(snapshot -> snapshotName.equals(snapshot.getName()))
                .findFirst()
                .orElse(null);
        if (targetSnapshot == null) {
            throw new IllegalArgumentException("Snapshot with name [" + snapshotName + "] not found in repository");
        }
        getRepoData().getIndices().forEach((indexName, rawIndex) -> {
            var snapshotNames = rawIndex.getSnapshots();
            if (snapshotNames == null || snapshotNames.isEmpty()) {
                log.atWarn()
                    .setMessage("Index [{}] skipped — no snapshots listed")
                    .addArgument(indexName)
                    .log();
            } else if (!snapshotNames.contains(targetSnapshot.getName())) {
                log.atWarn()
                    .setMessage("Index [{}] skipped — snapshot ID [{}] not found among {}")
                    .addArgument(indexName)
                    .addArgument(targetSnapshot.getId())
                    .addArgument(snapshotNames)
                    .log();
            } else {
                log.atInfo()
                    .setMessage("Matched index [{}] with snapshot [{}]")
                    .addArgument(indexName)
                    .addArgument(targetSnapshot.getName())
                    .log();
                matchedIndices.add(SnapshotRepoData_ES_5_4.Index.fromRawIndex(indexName, rawIndex));
            }
        });
        return matchedIndices;
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        return getRepoData().getSnapshots().stream()
                .filter(snapshot -> snapshot.getName().equals(snapshotName))
                .map(SnapshotRepoData_ES_5_4.Snapshot::getId)
                .findFirst()
                .orElse(null);
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

    public List<SnapshotRepoData_ES_5_4.Index> getIndices() {
        return getRepoData().getIndices().entrySet()
            .stream()
            .map(entry -> SnapshotRepoData_ES_5_4.Index.fromRawIndex(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    }
}
