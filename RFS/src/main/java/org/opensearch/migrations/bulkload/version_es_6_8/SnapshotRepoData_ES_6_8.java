package org.opensearch.migrations.bulkload.version_es_6_8;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CannotParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

public class SnapshotRepoData_ES_6_8 {
    
    @Getter
    private Path filePath;
    
    @Getter
    private List<Snapshot> snapshots;

    @Getter
    private Map<String, RawIndex> indices;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
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
    public static class RawIndex {
        private String id;
        @JsonDeserialize(using = SnapshotListDeserializer.class)
        private List<String> snapshots;
    }

    /**
     * Normalizes index->snapshots entries to a list of "snapshot keys".
     * Depending on source version, these keys may be:
     * - Snapshot UUIDs (ES 5.3+ AWS managed, ES 5.5+, ES 6.x+)
     * - Snapshot names (ES 5.0-5.4 standard layouts)
     * Consumers must accept either when resolving membership.
     * 
     * Supported formats:
     * - ES 5.0-5.4 standard: [{"name":"snap1"}, {"name":"snap2"}] → ["snap1", "snap2"]
     * - ES 5.3+ AWS managed: ["uuid1", "uuid2"] → ["uuid1", "uuid2"] 
     * - ES 5.5+: ["snap-name-1", "snap-name-2"] → ["snap-name-1", "snap-name-2"]
     */
    public static class SnapshotListDeserializer extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>(node.size());
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    // ES 5.5+ format OR AWS managed ES 5.3: ["uuid1", "uuid2"]
                    result.add(n.asText());
                } else if (n.isObject()) {
                    // ES 5.0-5.4 standard format: [{"name":"snap1"}, {"name":"snap2"}]
                    JsonNode name = n.get("name");
                    if (name != null && !name.isNull() && !name.asText().isEmpty()) {
                        result.add(name.asText());
                    }
                }
            }
            return result;
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class Index implements SnapshotRepo.Index {
        public static Index fromRawIndex(String name, RawIndex rawIndex) {
            return new Index(name, rawIndex.id, rawIndex.snapshots);
        }

        private final String name;
        private final String id;
        private final List<String> snapshots;
    }

    public static SnapshotRepoData_ES_6_8 fromRepo(SourceRepo repo) {
        Path file = repo.getSnapshotRepoDataFilePath();
        if (file == null) {
            throw new CannotParseRepoFile(repo);
        }
        return fromRepoFile(file);
    }

    public static SnapshotRepoData_ES_6_8 fromRepoFile(Path filePath) {
        ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
        try {
            SnapshotRepoData_ES_6_8 data = mapper.readValue(
                new File(filePath.toString()),
                SnapshotRepoData_ES_6_8.class
            );
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CannotParseRepoFile("Can't read or parse the Repo Metadata file: " + filePath.toString(), e);
        }
    }
}
