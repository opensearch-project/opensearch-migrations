package com.rfs.version_es_7_10;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.rfs.common.SourceRepo;
import com.rfs.common.SnapshotRepo;

public class SnapshotRepoData_ES_7_10 {
    public static SnapshotRepoData_ES_7_10 fromRepoFile(Path filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        SnapshotRepoData_ES_7_10 data = mapper.readValue(new File(filePath.toString()), SnapshotRepoData_ES_7_10.class);
        data.filePath = filePath;
        return data;
    }

    public static SnapshotRepoData_ES_7_10 fromRepo(SourceRepo repo) throws IOException {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new IOException("No index file found in " + repo.getRepoRootDir());
        }
        return fromRepoFile(file);
    }

    public Path filePath;
    public List<Snapshot> snapshots;
    public Map<String, RawIndex> indices;
    @JsonProperty("min_version")
    public String minVersion;
    @JsonProperty("index_metadata_identifiers")
    public Map<String, String> indexMetadataIdentifiers;

    public static class Snapshot implements SnapshotRepo.Snapshot {
        public String name;
        public String uuid;
        public int state;
        @JsonProperty("index_metadata_lookup")
        public Map<String, String> indexMetadataLookup;
        public String version;

        public String getName() {
            return name;
        }

        public String getId() {
            return uuid;
        }
    }

    public static class RawIndex {
        public String id;
        public List<String> snapshots;
        @JsonProperty("shard_generations")
        public List<String> shardGenerations;
    }

    public static class Index implements SnapshotRepo.Index {
        public static Index fromRawIndex(String name, RawIndex rawIndex) {
            Index index = new Index();
            index.name = name;
            index.id = rawIndex.id;
            index.snapshots = rawIndex.snapshots;
            index.shardGenerations = rawIndex.shardGenerations;
            return index;
        }

        public String name;
        public String id;
        public List<String> snapshots;
        public List<String> shardGenerations;

        public String getName() {
            return name;
        }

        public String getId() {
            return id;
        }

        public List<String> getSnapshots() {
            return snapshots;
        }
    }
}