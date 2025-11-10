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
    public static class SnapshotReference {
        private String value;
        private boolean isUuid;
        
        public static SnapshotReference fromName(String name) {
            return new SnapshotReference(name, false);
        }
        
        public static SnapshotReference fromUuid(String uuid) {
            return new SnapshotReference(uuid, true);
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawIndex {
        private String id;
        @JsonDeserialize(using = SnapshotListDeserializer.class)
        private List<SnapshotReference> snapshots;
    }

    /**
     * Normalizes index->snapshots entries to a list of snapshot references.
     * 
     * Supported formats:
     * - ES 5.0-5.4: [{"name":"snap1"}, {"name":"snap2"}] → [SnapshotReference(snap1, false), ...]
     * - ES 5.5, 6.x: ["snap-name-1", "snap-name-2"] → [SnapshotReference(snap-name-1, false), ...]
     */
    public static class SnapshotListDeserializer extends JsonDeserializer<List<SnapshotReference>> {
        @Override
        public List<SnapshotReference> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            
            List<SnapshotReference> result = new ArrayList<>();
            
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    // ES 5.5, 6.x: ["snap-name-1", "snap-name-2"] or ["uuid1", "uuid2"]
                    String value = n.asText();
                    boolean isUuid = value.length() == 22 && value.matches("[A-Za-z0-9_-]+");
                    result.add(new SnapshotReference(value, isUuid));
                } else if (n.isObject()) {
                    // ES 5.0-5.4: [{"name":"snap1"}, {"name":"snap2"}]
                    JsonNode name = n.get("name");
                    if (name != null && !name.isNull() && !name.asText().isEmpty()) {
                        result.add(SnapshotReference.fromName(name.asText()));
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
        private final List<SnapshotReference> snapshots;
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
