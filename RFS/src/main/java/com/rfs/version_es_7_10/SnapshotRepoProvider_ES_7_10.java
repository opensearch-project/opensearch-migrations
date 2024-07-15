package com.rfs.version_es_7_10;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;

public class SnapshotRepoProvider_ES_7_10 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_7_10 repoData = null;

    public SnapshotRepoProvider_ES_7_10(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_7_10 getRepoData() {
        if (repoData == null) {
            this.repoData = SnapshotRepoData_ES_7_10.fromRepo(repo);
        }
        return repoData;
    }

    public List<SnapshotRepo.Index> getIndices() {
        return getRepoData().indices.entrySet()
            .stream()
            .map(entry -> SnapshotRepoData_ES_7_10.Index.fromRawIndex(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        List<SnapshotRepo.Index> matchedIndices = new ArrayList<>();
        SnapshotRepoData_ES_7_10.Snapshot targetSnapshot = getRepoData().snapshots.stream()
            .filter(snapshot -> snapshotName.equals(snapshot.name))
            .findFirst()
            .orElse(null);

        if (targetSnapshot != null) {
            targetSnapshot.indexMetadataLookup.keySet().forEach(indexId -> {
                getRepoData().indices.forEach((indexName, rawIndex) -> {
                    if (indexId.equals(rawIndex.id)) {
                        matchedIndices.add(SnapshotRepoData_ES_7_10.Index.fromRawIndex(indexName, rawIndex));
                    }
                });
            });
        }
        return matchedIndices;
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        List<SnapshotRepo.Snapshot> convertedList = new ArrayList<>(getRepoData().snapshots);
        return convertedList;
    }

    public String getSnapshotId(String snapshotName) {
        for (SnapshotRepoData_ES_7_10.Snapshot snapshot : getRepoData().snapshots) {
            if (snapshot.name.equals(snapshotName)) {
                return snapshot.uuid;
            }
        }
        return null;
    }

    @Override
    public String getIndexId(String indexName) {
        return getRepoData().indices.get(indexName).id;
    }

    @Override
    public SourceRepo getRepo() {
        return repo;
    }

    public String getIndexMetadataId(String snapshotName, String indexName) {
        String indexId = getIndexId(indexName);
        if (indexId == null) {
            return null;
        }

        String metadataLookupKey = getRepoData().snapshots.stream()
            .filter(snapshot -> snapshot.name.equals(snapshotName))
            .map(snapshot -> snapshot.indexMetadataLookup.get(indexId))
            .findFirst()
            .orElse(null);
        if (metadataLookupKey == null) {
            return null;
        }

        return getRepoData().indexMetadataIdentifiers.get(metadataLookupKey);
    }
}
