package org.opensearch.migrations.bulkload.version_es_5_3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CantParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

public class SnapshotRepoData_ES_5_3 {

    public static SnapshotRepoData_ES_5_3 fromRepoFile(Path filePath) {
        ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
        try {
            SnapshotRepoData_ES_5_3 data = mapper.readValue(
                    new File(filePath.toString()),
                    SnapshotRepoData_ES_5_3.class
            );
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CantParseRepoFile("Can't read or parse the Repo Metadata file: " + filePath.toString(), e);
        }
    }

    public static SnapshotRepoData_ES_5_3 fromRepo(SourceRepo repo) {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new CantParseRepoFile("No index file found in " + repo.getRepoRootDir());
        }
        return fromRepoFile(file);
    }

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
        private int state;

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

        @JsonProperty("timestamp")
        private Long timestamp;

        @JsonProperty("state")
        private String state;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RawIndex {
        private String id;

        @JsonProperty("snapshots")
        private List<Object> rawSnapshots;

        @JsonIgnore
        public List<String> getSnapshots() {
            if (rawSnapshots == null) return List.of();
            return rawSnapshots.stream()
                    .map(o -> {
                        if (o instanceof String) {
                            return (String) o;
                        } else if (o instanceof RawSnapshot) {
                            return ((RawSnapshot) o).getName();
                        } else if (o instanceof Map) {
                            Object name = ((Map<?, ?>) o).get("name");
                            if (name != null) {
                                return name.toString();
                            } else {
                                throw new IllegalStateException("Map snapshot entry missing 'name': " + o);
                            }
                        } else {
                            throw new IllegalStateException("Unknown snapshot entry type: " + o.getClass());
                        }
                    })
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
}
