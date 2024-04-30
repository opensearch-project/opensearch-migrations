package com.rfs.version_es_7_10;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.rfs.common.SourceRepo;
import com.rfs.common.SnapshotRepo;

public class SnapshotRepoProvider_ES_7_10 implements SnapshotRepo.Provider {
    private final SnapshotRepoData_ES_7_10 repoData;

    public SnapshotRepoProvider_ES_7_10(SourceRepo repo) throws IOException{
        this.repoData = SnapshotRepoData_ES_7_10.fromRepo(repo);
    }

    public List<SnapshotRepo.Index> getIndices() {
        return repoData.indices.entrySet().stream()
                .map(entry -> SnapshotRepoData_ES_7_10.Index.fromRawIndex(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        List<SnapshotRepo.Index> matchedIndices = new ArrayList<>();
        SnapshotRepoData_ES_7_10.Snapshot targetSnapshot = repoData.snapshots.stream()
            .filter(snapshot -> snapshotName.equals(snapshot.name))
            .findFirst()
            .orElse(null);

        if (targetSnapshot != null) {
            targetSnapshot.indexMetadataLookup.keySet().forEach(indexId -> {
                repoData.indices.forEach((indexName, rawIndex) -> {
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
        List<SnapshotRepo.Snapshot> convertedList = new ArrayList<>(repoData.snapshots);
        return convertedList;
    }
    
    public String getSnapshotId(String snapshotName) {
        for (SnapshotRepoData_ES_7_10.Snapshot snapshot : repoData.snapshots) {
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

    public String getIndexMetadataId (String snapshotName, String indexName) {
        String indexId = getIndexId(indexName);
        if (indexId == null) {
            return null;
        }

        String metadataLookupKey = repoData.snapshots.stream()
                .filter(snapshot -> snapshot.name.equals(snapshotName))
                .map(snapshot -> snapshot.indexMetadataLookup.get(indexId))
                .findFirst()
                .orElse(null);
        if (metadataLookupKey == null) {
            return null;
        }

        return repoData.indexMetadataIdentifiers.get(metadataLookupKey);
    }
}
