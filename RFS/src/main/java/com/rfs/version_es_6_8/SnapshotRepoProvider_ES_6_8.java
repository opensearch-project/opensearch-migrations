package com.rfs.version_es_6_8;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.rfs.common.SourceRepo;
import com.rfs.common.SnapshotRepo;

public class SnapshotRepoProvider_ES_6_8 implements SnapshotRepo.Provider {
    private final SnapshotRepoData_ES_6_8 repoData;

    public SnapshotRepoProvider_ES_6_8(SourceRepo repo) throws IOException{
        this.repoData = SnapshotRepoData_ES_6_8.fromRepo(repo);
    }

    public List<SnapshotRepoData_ES_6_8.Index> getIndices() {
        return repoData.indices.entrySet().stream()
                .map(entry -> SnapshotRepoData_ES_6_8.Index.fromRawIndex(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        List<SnapshotRepo.Index> matchedIndices = new ArrayList<>();
        SnapshotRepoData_ES_6_8.Snapshot targetSnapshot = repoData.snapshots.stream()
            .filter(snapshot -> snapshotName.equals(snapshot.name))
            .findFirst()
            .orElse(null);

        if (targetSnapshot != null) {
            repoData.indices.forEach((indexName, rawIndex) -> {
                if (rawIndex.snapshots.contains(targetSnapshot.uuid)) {
                    matchedIndices.add(SnapshotRepoData_ES_6_8.Index.fromRawIndex(indexName, rawIndex));
                }
            });
        }
        return matchedIndices;
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        List<SnapshotRepo.Snapshot> convertedList = new ArrayList<>(repoData.snapshots);
        return convertedList;
    }
    
    @Override
    public String getSnapshotId(String snapshotName) {
        for (SnapshotRepoData_ES_6_8.Snapshot snapshot : repoData.snapshots) {
            if (snapshot.name.equals(snapshotName)) {
                return snapshot.uuid;
            }
        }
        return null;
    }

    @Override
    public String getIndexId(String indexName) {
        return repoData.indices.get(indexName).id;
    }
}
