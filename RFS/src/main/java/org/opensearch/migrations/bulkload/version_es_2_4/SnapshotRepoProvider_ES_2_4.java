package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

public class SnapshotRepoProvider_ES_2_4 implements SnapshotRepo.Provider {
    private static final String INDICES_DIR_NAME = "indices";
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
        Path indicesRoot = repo.getRepoRootDir().resolve(INDICES_DIR_NAME);
        File[] indexDirs = indicesRoot.toFile().listFiles();
        if (indexDirs == null) {
            return Collections.emptyList();
        }
        for (File indexDir : indexDirs) {
            if (!indexDir.isDirectory()) {
                continue;
            }

            if (containsMetaFile(indexDir, snapshotName)) {
                result.add(new SimpleIndex(indexDir.getName(), snapshotName));
            }
        }
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

    private boolean containsMetaFile(File dir, String snapshotName) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.getName().equals("meta-" + snapshotName + ".dat")) {
                return true;
            }
        }
        return false;
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
            return name;
        }
    }
}
