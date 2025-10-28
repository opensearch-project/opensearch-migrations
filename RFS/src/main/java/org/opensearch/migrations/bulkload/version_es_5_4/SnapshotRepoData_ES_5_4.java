package org.opensearch.migrations.bulkload.version_es_5_4;

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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
        private String uuid;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RawIndex {
        private String id;

        @JsonProperty("snapshots")
        @JsonDeserialize(using = NameOrUuidListDeserializer.class)
        private List<String> rawSnapshotKeys;

        @JsonIgnore
        public List<String> getSnapshots() {
            return rawSnapshotKeys == null ? List.of() : rawSnapshotKeys;
        }
    }

    /**
     * Accepts BOTH:
     *  - Array of strings containing UUIDs ["UUID", ...] (as observed in managed-service snapshots)
     *  - Array of Objects containing Name and UUIDs [{"name":"...","uuid":"..."}, ...] (as observed in self-managed snapshots)
     * Normalizes to a list of "keys": prefer name if present, else uuid.
     */
    public static class NameOrUuidListDeserializer extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    out.add(n.asText()); // UUID-only array
                } else if (n.isObject()) {
                    JsonNode name = n.get("name");
                    JsonNode uuid = n.get("uuid");
                    if (name != null && !name.isNull() && !name.asText().isEmpty()) {
                        out.add(name.asText()); // prefer name if present
                    } else if (uuid != null && !uuid.isNull()) {
                        out.add(uuid.asText());
                    }
                }
            }
            return out;
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
        private final List<String> snapshots; // keys: name or uuid depending on source
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
