package org.opensearch.migrations.bulkload.version_es_7_10;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.InvalidSnapshotFormatException;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_7_10 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_7_10 repoData = null;
    private final Map<String, List<SnapshotRepo.Index>> indicesBySnapshotName = new HashMap<>();
    private Map<String, SnapshotRepo.Index> indicesById;

    public SnapshotRepoProvider_ES_7_10(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_7_10 getRepoData() {
        if (repoData == null) {
            this.repoData = SnapshotRepoData_ES_7_10.fromRepo(repo);
        }
        return repoData;
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        return new ArrayList<>(getRepoData().getSnapshots());
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        return indicesBySnapshotName.computeIfAbsent(snapshotName, this::loadIndicesInSnapshot);
    }

    private List<SnapshotRepo.Index> loadIndicesInSnapshot(String snapshotName) {
        List<SnapshotRepo.Index> matchedIndices = new ArrayList<>();
        SnapshotRepoData_ES_7_10.Snapshot targetSnapshot = getRepoData().getSnapshots().stream()
            .filter(snapshot -> snapshotName.equals(snapshot.getName()))
            .findFirst()
            .orElse(null);

        if (targetSnapshot != null) {
            var indexMetadataLookup = targetSnapshot.getIndexMetadataLookup();
            if (indexMetadataLookup == null) {
                throw new InvalidSnapshotFormatException();
            }
            var indexLookup = getIndicesById();
            indexMetadataLookup.keySet().forEach(indexId -> {
                var index = indexLookup.get(indexId);
                if (index != null) {
                    matchedIndices.add(index);
                }
            });
        }
        return List.copyOf(matchedIndices);
    }

    private Map<String, SnapshotRepo.Index> getIndicesById() {
        if (indicesById == null) {
            indicesById = new HashMap<>();
            getRepoData().getIndices().forEach((indexName, rawIndex) ->
                indicesById.put(
                    rawIndex.getId(),
                    SnapshotRepoData_ES_7_10.Index.fromRawIndex(indexName, rawIndex)
                )
            );
        }
        return indicesById;
    }

    public String getSnapshotId(String snapshotName) {
        for (SnapshotRepoData_ES_7_10.Snapshot snapshot : getRepoData().getSnapshots()) {
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

    public List<SnapshotRepo.Index> getIndices() {
        return getRepoData().getIndices().entrySet()
            .stream()
            .map(entry -> SnapshotRepoData_ES_7_10.Index.fromRawIndex(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    }

    public String getIndexMetadataId(String snapshotName, String indexName) {
        String indexId = getIndexId(indexName);
        if (indexId == null) {
            return null;
        }

        String metadataLookupKey = getRepoData().getSnapshots().stream()
            .filter(snapshot -> snapshot.getName().equals(snapshotName))
            .map(snapshot -> snapshot.getIndexMetadataLookup().get(indexId))
            .findFirst()
            .orElse(null);
        if (metadataLookupKey == null) {
            return null;
        }

        return getRepoData().getIndexMetadataIdentifiers().get(metadataLookupKey);
    }
}
