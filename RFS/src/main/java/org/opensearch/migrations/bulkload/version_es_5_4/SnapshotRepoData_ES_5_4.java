package org.opensearch.migrations.bulkload.version_es_5_4;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CannotParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

public class SnapshotRepoData_ES_5_4 {
    
    @Getter
    private Path filePath;

    @Getter
    private List<Snapshot> snapshots;

    @Getter
    private Map<String, RawIndex> indices;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Snapshot implements SnapshotRepo.Snapshot {
        private String name;
        private String uuid;

        @Override
        public String getId() {
            return uuid;
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawSnapshot {
        private String name;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RawIndex {
        private String id;

        @JsonProperty("snapshots")
        private List<RawSnapshot> rawSnapshots;

        @JsonIgnore
        public List<String> getSnapshots() {
            if (rawSnapshots == null) return List.of();
            return rawSnapshots.stream()
                .map(RawSnapshot::getName)
                .collect(Collectors.toList());
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class Index implements SnapshotRepo.Index {
        public static Index fromRawIndex(String name, RawIndex rawIndex) {
            return new Index(name, rawIndex.id, rawIndex.getSnapshots());
        }

        private final String name;
        private final String id;
        private final List<String> snapshots;
    }

    public static SnapshotRepoData_ES_5_4 fromRepo(SourceRepo repo) {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new CannotParseRepoFile(repo);
        }
        return fromRepoFile(file);
    }

    public static SnapshotRepoData_ES_5_4 fromRepoFile(Path filePath) {
        ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
        try {
            SnapshotRepoData_ES_5_4 data = mapper.readValue(
                    new File(filePath.toString()),
                    SnapshotRepoData_ES_5_4.class
            );
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CannotParseRepoFile("Can't read or parse the Repo Metadata file: " + filePath.toString(), e);
        }
    }
}
