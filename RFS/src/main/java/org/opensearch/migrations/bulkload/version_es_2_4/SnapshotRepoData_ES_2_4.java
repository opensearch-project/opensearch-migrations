package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.CantParseRepoFile;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor
public class SnapshotRepoData_ES_2_4 {

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
    public static SnapshotRepoData_ES_2_4 fromRepo(SourceRepo repo) {
        Path indexFile = repo.getRepoRootDir().resolve("index");
        if (!indexFile.toFile().exists()) {
            throw new CantParseRepoFile("No 'index' file found in " + repo.getRepoRootDir());
        }
        return fromRepoFile(indexFile);
    }

    /**
     * Parses the given file as a SnapshotRepoData_ES_2_4
     * Sets the filePath field for traceability.
     */
    public static SnapshotRepoData_ES_2_4 fromRepoFile(Path filePath) {
        try {
            log.debug("Reading ES 2.4 repo 'index' file: {}", filePath);
            ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
            SnapshotRepoData_ES_2_4 data = mapper.readValue(filePath.toFile(), SnapshotRepoData_ES_2_4.class);
            data.filePath = filePath;

            if (data.snapshots == null || data.snapshots.isEmpty()) {
                log.warn("Loaded ES 2.4 'index' file but found no snapshots! File: {}", filePath);
            } else {
                log.info("Loaded ES 2.4 'index' file with snapshots: {}", data.snapshots);
            }

            return data;
        } catch (IOException e) {
            throw new CantParseRepoFile("Can't read or parse the ES 2.4 repo metadata file: " + filePath, e);
        }
    }

    @Override
    public String toString() {
        return "SnapshotRepoData_ES_2_4{filePath=" + filePath + ", snapshots=" + snapshots + '}';
    }
}
