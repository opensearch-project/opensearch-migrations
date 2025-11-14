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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor // for Jackson
public class SnapshotRepoData_ES_6_8 {
    
    @Getter
    private Path filePath;
    
    @Getter
    @JsonProperty("snapshots")
    private List<Snapshot> snapshots;

    @Getter
    @JsonProperty("indices")
    private Map<String, RawIndex> indices;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Builder
    public static class Snapshot implements SnapshotRepo.Snapshot {
        private String name;
        @JsonProperty("uuid")
        private String id;
        private int state;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawIndex {
        private String id;
        @JsonDeserialize(using = SnapshotListDeserializer.class)
        private List<Snapshot> snapshots;
    }

    /**
     * Normalizes different snapshot reference formats across ES versions.
     * Different support layouts of snapshots containing indices:
     * - ES 5.0-5.4: Array of objects with name → [{"name":"snap1"}, {"name":"snap2"}]
     * - ES 5.5-7.8: Array of UUID strings → ["uuid1", "uuid2"]
     * Both formats are normalized to Snapshot objects for unified processing.
     */
    public static class SnapshotListDeserializer extends JsonDeserializer<List<Snapshot>> {
        @Override
        public List<Snapshot> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            
            List<Snapshot> result = new ArrayList<>();
            
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    // ES 5.5, 6.x: ["uuid1", "uuid2"]
                    var id = n.asText();
                    result.add(Snapshot.builder().id(id).build());
                } else if (n.isObject()) {
                    // ES 5.0-5.4: [{"name":"snap1"}, {"name":"snap2"}]
                    var name = n.get("name").asText();
                    result.add(Snapshot.builder().name(name).build());
                }
            }
            
            return result;
        }
    }

    @Getter
    @AllArgsConstructor
    public static class Index implements SnapshotRepo.Index {
        private final String name;
        private final String id;
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
