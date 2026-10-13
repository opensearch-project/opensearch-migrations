package org.opensearch.migrations.bulkload.version_es_6_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_6_8 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_6_8 repoData = null;
    private final Map<String, List<SnapshotRepo.Index>> indicesBySnapshotName = new HashMap<>();
    private Map<String, List<SnapshotRepo.Index>> indicesBySnapshotReference;

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
     * Builds a snapshot-reference lookup once, then caches the result for each requested snapshot.
     */
    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        return indicesBySnapshotName.computeIfAbsent(snapshotName, this::loadIndicesInSnapshot);
    }

    private List<SnapshotRepo.Index> loadIndicesInSnapshot(String snapshotName) {
        return getSnapshotForName(snapshotName)
            .map(this::getIndicesForSnapshot)
            .orElse(List.of());
    }

    private List<SnapshotRepo.Index> getIndicesForSnapshot(SnapshotRepo.Snapshot snapshot) {
        var matchedByName = new LinkedHashMap<String, SnapshotRepo.Index>();
        addMatches(matchedByName, snapshot.getName());
        addMatches(matchedByName, snapshot.getId());
        return List.copyOf(matchedByName.values());
    }

    private void addMatches(Map<String, SnapshotRepo.Index> matchedIndices, String snapshotReference) {
        if (snapshotReference == null) {
            return;
        }
        getIndicesBySnapshotReference().getOrDefault(snapshotReference, List.of())
            .forEach(index -> matchedIndices.putIfAbsent(index.getName(), index));
    }

    private Map<String, List<SnapshotRepo.Index>> getIndicesBySnapshotReference() {
        if (indicesBySnapshotReference == null) {
            var lookup = new HashMap<String, List<SnapshotRepo.Index>>();
            getRepoData().getIndices().forEach((indexName, rawIndex) -> {
                var index = new SnapshotRepoData_ES_6_8.Index(indexName, rawIndex.getId());
                for (var snapshotReference : rawIndex.getSnapshots()) {
                    addReference(lookup, snapshotReference.getName(), index);
                    addReference(lookup, snapshotReference.getId(), index);
                }
            });
            lookup.replaceAll((key, indices) -> List.copyOf(indices));
            indicesBySnapshotReference = lookup;
        }
        return indicesBySnapshotReference;
    }

    private static void addReference(
        Map<String, List<SnapshotRepo.Index>> lookup,
        String snapshotReference,
        SnapshotRepo.Index index
    ) {
        if (snapshotReference != null) {
            lookup.computeIfAbsent(snapshotReference, key -> new ArrayList<>()).add(index);
        }
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
