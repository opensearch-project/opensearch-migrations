package com.rfs.version_es_6_8;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotRepo.CantParseRepoFile;
import com.rfs.common.SourceRepo;

public class SnapshotRepoData_ES_6_8 {

    public static SnapshotRepoData_ES_6_8 fromRepoFile(Path filePath) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            SnapshotRepoData_ES_6_8 data = mapper.readValue(
                new File(filePath.toString()),
                SnapshotRepoData_ES_6_8.class
            );
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CantParseRepoFile("Can't read or parse the Repo Metadata file: " + filePath.toString(), e);
        }
    }

    public static SnapshotRepoData_ES_6_8 fromRepo(SourceRepo repo) {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new CantParseRepoFile("No index file found in " + repo.getRepoRootDir());
        }
        return fromRepoFile(file);
    }

    public Path filePath;
    public List<Snapshot> snapshots;
    public Map<String, RawIndex> indices;

    public static class Snapshot implements SnapshotRepo.Snapshot {
        public String name;
        public String uuid;
        public int state;

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getId() {
            return uuid;
        }
    }

    public static class RawIndex {
        public String id;
        public List<String> snapshots;
    }

    public static class Index implements SnapshotRepo.Index {
        public static Index fromRawIndex(String name, RawIndex rawIndex) {
            Index index = new Index();
            index.name = name;
            index.id = rawIndex.id;
            index.snapshots = rawIndex.snapshots;
            return index;
        }

        public String name;
        public String id;
        public List<String> snapshots;

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public List<String> getSnapshots() {
            return snapshots;
        }
    }
}
