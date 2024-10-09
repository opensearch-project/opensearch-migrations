package org.opensearch.migrations.bulkload.version_es_7_10;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CantParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Getter
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

    private Path filePath;
    private List<Snapshot> snapshots;
    private Map<String, RawIndex> indices;
    @JsonProperty("min_version")
    private String minVersion;
    @JsonProperty("index_metadata_identifiers")
    private Map<String, String> indexMetadataIdentifiers;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Snapshot implements SnapshotRepo.Snapshot {
        private String name;
        private String uuid;
        private int state;
        @JsonProperty("index_metadata_lookup")
        private Map<String, String> indexMetadataLookup;
        private String version;

        @Override
        public String getId() {
            return uuid;
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RawIndex {
        private String id;
        private List<String> snapshots;
        @JsonProperty("shard_generations")
        private List<String> shardGenerations;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Index implements SnapshotRepo.Index {
        public static Index fromRawIndex(String name, RawIndex rawIndex) {
            return new Index(name, rawIndex.id, rawIndex.snapshots, rawIndex.shardGenerations);
        }

        private final String name;
        private final String id;
        private final List<String> snapshots;
        private final List<String> shardGenerations;
    }
}
