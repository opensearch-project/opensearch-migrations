package org.opensearch.migrations.bulkload.version_es_7_10;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CannotParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Getter
@NoArgsConstructor // for Jackson
public class SnapshotRepoData_ES_7_10 {
    private Path filePath;
    @JsonProperty("snapshots")
    private List<Snapshot> snapshots;
    @JsonProperty("indices")
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
            return new Index(name, rawIndex.id, rawIndex.shardGenerations);
        }

        private final String name;
        private final String id;
        private final List<String> shardGenerations;
    }

    public static SnapshotRepoData_ES_7_10 fromRepoFile(Path filePath) {
        ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
        try {
            SnapshotRepoData_ES_7_10 data = mapper.readValue(
                new File(filePath.toString()),
                SnapshotRepoData_ES_7_10.class
            );
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CannotParseRepoFile("Can't read or parse the Repo Metadata file: " + filePath.toString(), e);
        }
    }

    public static SnapshotRepoData_ES_7_10 fromRepo(SourceRepo repo) {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new CannotParseRepoFile(repo);
        }
        return fromRepoFile(file);
    }
}
