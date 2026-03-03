package org.opensearch.migrations.bulkload.version_es_1_7;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.databind.JsonNode;

import static org.opensearch.migrations.bulkload.version_es_1_7.ElasticsearchConstants_ES_1_7.INDICES_DIR_NAME;

public class SnapshotRepoProvider_ES_1_7 implements SnapshotRepoES17 {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_1_7 repoData;

    public SnapshotRepoProvider_ES_1_7(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_1_7 getRepoData() {
        if (repoData == null) {
            repoData = SnapshotRepoData_ES_1_7.fromRepo(repo);
        }
        return repoData;
    }

    @Override
    public byte[] getIndexMetadataFile(String indexName, String snapshotName) {
        Path metaFile = repo.getIndexMetadataFilePath(indexName, snapshotName);
        try {
            return Files.readAllBytes(metaFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read snapshot file for index: " + indexName, e);
        }
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        List<SnapshotRepo.Snapshot> result = new ArrayList<>();
        for (String name : getRepoData().getSnapshots()) {
            result.add(new SimpleSnapshot(name));
        }
        return result;
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        Path snapshotMetaFile = repo.getSnapshotMetadataFilePath(snapshotName);
        List<SnapshotRepo.Index> result = new ArrayList<>();

        try {
            var node = ObjectMapperFactory.createDefaultMapper().readTree(snapshotMetaFile.toFile());

            // ES 1x SnapMetadata file snap-<> is plain JSON
            // This file has a nested JSON structure where top level field is "snapshot"
            // and the nested field is "indices" which holds the list of indices in snapshot
            JsonNode indicesArray = node.path("snapshot").path(INDICES_DIR_NAME);
            if (indicesArray == null || !indicesArray.isArray()) {
                return Collections.emptyList();
            }
            for (JsonNode indexNode : indicesArray) {
                result.add(new SimpleIndex(indexNode.asText(), snapshotName));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read snap metadata for snapshot=" + snapshotName, e);
        }

        return result;
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return repo.getShardMetadataFilePath(snapshotId, indexId, shardId);
    }

    public Path getGlobalMetadataFile(String snapshotName) {
        return repo.getGlobalMetadataFilePath(snapshotName);
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        return snapshotName;
    }

    @Override
    public String getIndexId(String indexName) {
        return indexName;
    }

    @Override
    public SourceRepo getRepo() {
        return repo;
    }

    // Simple classes for snapshot and index listing
    public static class SimpleSnapshot implements SnapshotRepo.Snapshot {
        private final String name;

        public SimpleSnapshot(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getId() {
            return getName();
        }
    }

    public static class SimpleIndex implements SnapshotRepo.Index {
        private final String name;

        public SimpleIndex(String name, String snapshotName) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getId() {
            return getName();
        }

        @Override
        public String toString() {
            return getName();
        }
    }
}
