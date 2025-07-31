package org.opensearch.migrations.bulkload.version_es_1_7;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;


public class SnapshotRepoProvider_ES_1_7 implements SnapshotRepoES17 {
    private static final String SNAPSHOT_PREFIX = "snapshot-";
    private static final String INDICES_DIR_NAME = "indices";
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
        Path metaFile = repo.getSnapshotRepoDataFilePath().getParent()
                .resolve(INDICES_DIR_NAME)
                .resolve(indexName)
                .resolve(SNAPSHOT_PREFIX + snapshotName);

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

            // ES 1x snap-<>.dat file is plain JSON
            JsonNode indicesNode = node.get("indices");
            if (indicesNode == null || !indicesNode.isObject()) {
                return Collections.emptyList();
            }
            indicesNode.fieldNames().forEachRemaining(indexName ->
                    result.add(new SimpleIndex(indexName, snapshotName))
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read snapshot metadata for snapshot=" + snapshotName, e);
        }

        return result;
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return repo.getSnapshotRepoDataFilePath().getParent()
                .resolve(INDICES_DIR_NAME)
                .resolve(indexId)
                .resolve(String.valueOf(shardId))
                .resolve(SNAPSHOT_PREFIX + snapshotId);
    }

    public Path getSnapshotMetadataFile(String snapshotName) {
        return repo.getSnapshotRepoDataFilePath().getParent().resolve("metadata-" + snapshotName);
    }

    private boolean containsSnapshotFile(File dir, String snapshotName) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.getName().equals(SNAPSHOT_PREFIX + snapshotName)) {
                return true;
            }
        }
        return false;
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
        private final String snapshotName;

        public SimpleIndex(String name, String snapshotName) {
            this.name = name;
            this.snapshotName = snapshotName;
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
        public List<String> getSnapshots() {
            return Collections.singletonList(snapshotName);
        }

        @Override
        public String toString() {
            return getName();
        }
    }
}
