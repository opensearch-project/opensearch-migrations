package com.rfs.version_es_7_10;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotRepo.CantParseRepoFile;
import com.rfs.common.SourceRepo;

public class SnapshotRepoData_ES_7_10 {
    public static SnapshotRepoData_ES_7_10 fromRepoFile(Path filePath) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            SnapshotRepoData_ES_7_10 data = mapper.readValue(
                new File(filePath.toString()),
                SnapshotRepoData_ES_7_10.class
            );
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CantParseRepoFile("Can't read or parse the Repo Metadata file: " + filePath.toString(), e);
        }
    }

    public static SnapshotRepoData_ES_7_10 fromRepo(SourceRepo repo) {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new CantParseRepoFile("No index file found in " + repo.getRepoRootDir());
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
