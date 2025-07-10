package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnapshotRepoProvider_ES_2_4 implements SnapshotRepo_ES_2_4 {

    private final SourceRepo repo;
    private SnapshotRepoData_ES_2_4 repoData;

    public SnapshotRepoProvider_ES_2_4(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_2_4 getRepoData() {
        if (repoData == null) {
            repoData = SnapshotRepoData_ES_2_4.fromRepo(repo);
        }
        return repoData;
    }

    @Override
    public List<String> listIndices() {
        List<String> indexNames = new ArrayList<>();
        Path indicesRoot = repo.getRepoRootDir().resolve("indices");

        File[] children = indicesRoot.toFile().listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) {
                    indexNames.add(f.getName());
                }
            }
        }
        log.info("listIndices found: {}", indexNames);
        return indexNames;
    }

    @Override
    public byte[] getIndexMetadataFile(String indexName) {
        // ES 2.4 snapshot has exactly ONE snapshot
        String snapshotName = getSnapshots().get(0).getName();
        Path metaFile = repo.getRepoRootDir()
            .resolve("indices")
            .resolve(indexName)
            .resolve("meta-" + snapshotName + ".dat");

        try {
            return Files.readAllBytes(metaFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read meta file for index: " + indexName, e);
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
        List<SnapshotRepo.Index> result = new ArrayList<>();
        Path indicesRoot = repo.getRepoRootDir().resolve("indices");

        File[] indexDirs = indicesRoot.toFile().listFiles();
        if (indexDirs == null) {
            log.warn("No indices directory found in repo: {}", indicesRoot);
            return Collections.emptyList();
        }

        for (File indexDir : indexDirs) {
            if (!indexDir.isDirectory()) {
                continue;
            }

            // Look for meta-<snapshotName>.dat inside this index dir
            File[] files = indexDir.listFiles();
            if (files == null) {
                continue;
            }

            boolean foundMeta = false;
            for (File f : files) {
                if (f.getName().equals("meta-" + snapshotName + ".dat")) {
                    foundMeta = true;
                    break;
                }
            }

            if (foundMeta) {
                log.info("Index [{}] contains snapshot [{}]", indexDir.getName(), snapshotName);
                result.add(new SimpleIndex(indexDir.getName(), snapshotName));
            }
        }

        log.info("getIndicesInSnapshot for snapshot [{}] found indices: {}", snapshotName, result);
        return result;
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        for (String name : getRepoData().getSnapshots()) {
            if (name.equals(snapshotName)) {
                return name;
            }
        }
        return null;
    }

    @Override
    public String getIndexId(String indexName) {
        return indexName;
    }

    @Override
    public SourceRepo getRepo() {
        return repo;
    }

    @Override
    public SnapshotRepo.Provider getDelegateRepo() {
        return this;
    }

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
            return name;
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
            return name;
        }

        @Override
        public List<String> getSnapshots() {
            return Collections.singletonList(snapshotName);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
