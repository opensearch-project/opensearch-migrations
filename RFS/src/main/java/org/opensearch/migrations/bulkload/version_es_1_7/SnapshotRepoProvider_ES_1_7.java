package org.opensearch.migrations.bulkload.version_es_1_7;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_1_7 implements SnapshotRepoES17 {
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

    public List<String> listIndices() {
        List<String> indexNames = new ArrayList<>();
        Path indicesRoot = repo.getRepoRootDir().resolve(INDICES_DIR_NAME);
        File[] children = indicesRoot.toFile().listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) {
                    indexNames.add(f.getName());
                }
            }
        }
        return indexNames;
    }

    @Override
    public byte[] getIndexMetadataFile(String indexName, String snapshotName) {
        Path metaFile = repo.getRepoRootDir()
                .resolve(INDICES_DIR_NAME)
                .resolve(indexName)
                .resolve("snapshot-" + snapshotName);

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
        // Very similar logic as SnapshotRepoProvider_ES_2_4 but different file name
        List<SnapshotRepo.Index> result = new ArrayList<>();
        Path indicesRoot = repo.getRepoRootDir().resolve(INDICES_DIR_NAME);
        File[] indexDirs = indicesRoot.toFile().listFiles();
        if (indexDirs == null) {
            return Collections.emptyList();
        }
        for (File indexDir : indexDirs) {
            if (!indexDir.isDirectory()) {
                continue;
            }

            if (containsSnapshotFile(indexDir, snapshotName)) {
                result.add(new SimpleIndex(indexDir.getName(), snapshotName));
            }
        }
        return result;
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return repo.getRepoRootDir()
                .resolve(INDICES_DIR_NAME)
                .resolve(indexId)
                .resolve(String.valueOf(shardId))
                .resolve("snapshot-" + snapshotId);
    }

    public Path getSnapshotMetadataFile(String snapshotName) {
        return repo.getRepoRootDir().resolve("metadata-" + snapshotName);
    }

    private boolean containsSnapshotFile(File dir, String snapshotName) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.getName().equals("snapshot-" + snapshotName)) {
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
