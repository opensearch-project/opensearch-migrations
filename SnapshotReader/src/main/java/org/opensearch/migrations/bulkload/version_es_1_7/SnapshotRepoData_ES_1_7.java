package org.opensearch.migrations.bulkload.version_es_1_7;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CannotParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class SnapshotRepoData_ES_1_7 {

    /**
     * The path to the 'index' file that was read.
     * Not part of the ES JSON, set by loader.
     */
    @Getter
    private Path filePath;

    /**
     * Actual list of snapshot names, read from JSON:
     * {
     *   "snapshots": ["snap1", "snap2"]
     * }
     */
    @Getter
    @JsonProperty("snapshots")
    private List<String> snapshots;

    /**
     * Loads and parses the top-level 'index' file in this repo.
     * This is the entry point for reading the snapshot list.
     */
    public static SnapshotRepoData_ES_1_7 fromRepo(SourceRepo repo) {
        Path indexFile = repo.getSnapshotRepoDataFilePath();
        if (!indexFile.toFile().exists()) {
            throw new CannotParseRepoFile(repo);
        }
        return fromRepoFile(indexFile);
    }

    /**
     * Parses the given file as a SnapshotRepoData_ES_1_7
     * Sets the filePath field for traceability.
     */
    public static SnapshotRepoData_ES_1_7 fromRepoFile(Path filePath) {
        try {
            ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
            SnapshotRepoData_ES_1_7 data = mapper.readValue(filePath.toFile(), SnapshotRepoData_ES_1_7.class);
            data.filePath = filePath;
            return data;
        } catch (IOException e) {
            throw new CannotParseRepoFile("Can't read or parse the ES 1.7 repo metadata file: " + filePath, e);
        }
    }

    @Override
    public String toString() {
        return "SnapshotRepoData_ES_1_7{filePath=" + filePath + ", snapshots=" + snapshots + '}';
    }
}
