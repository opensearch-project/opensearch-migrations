package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnapshotRepoProvider_ES_2_4 implements SnapshotRepo.Provider {
    private final SourceRepo repo;
    private SnapshotRepoData_ES_2_4 repoData;

    public SnapshotRepoProvider_ES_2_4(SourceRepo repo) {
        this.repo = repo;
    }

    protected SnapshotRepoData_ES_2_4 getRepoData() {
        if (repoData == null) {
            repoData = SnapshotRepoData_ES_2_4.fromRepo(repo);
        }
        return repoData;
    }

    @Override
    public List<SnapshotRepo.Snapshot> getSnapshots() {
        List<SnapshotRepo.Snapshot> result = new ArrayList<>();
        for (String name : getRepoData().getSnapshots()) {
            result.add(new SimpleSnapshot(name));
        }
        return result;
    }

    @Override
    public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName) {
        Path snapshotMetaFile = repo.getSnapshotMetadataFilePath(snapshotName);
        ObjectMapper smileMapper = new ObjectMapper(ElasticsearchConstants_ES_2_4.SMILE_FACTORY);

        try {
            byte[] allBytes = Files.readAllBytes(snapshotMetaFile);
            int smileStart = findSmileHeaderOffset(allBytes);
            if (smileStart < 0) {
                throw new IllegalStateException("SMILE header not found in snapshot metadata file: " + snapshotMetaFile);
            }

            try (InputStream in = new ByteArrayInputStream(allBytes, smileStart, allBytes.length - smileStart)) {
                JsonNode rootNode = smileMapper.readTree(in);

                // Get the 'snapshot' node
                JsonNode snapshotNode = rootNode.get("snapshot");
                if (snapshotNode == null || !snapshotNode.isObject()) {
                    log.atWarn()
                        .setMessage("No 'snapshot' object found in snapshot metadata for [{}]")
                        .addArgument(snapshotName)
                        .log();
                    return Collections.emptyList();
                }

                // Get the 'indices' array inside 'snapshot'
                JsonNode indicesNode = snapshotNode.get("indices");
                if (indicesNode == null || !indicesNode.isArray()) {
                    log.atWarn()
                        .setMessage("No 'indices' array found in snapshot metadata for [{}]")
                        .addArgument(snapshotName)
                        .log();
                    return Collections.emptyList();
                }

                List<SnapshotRepo.Index> result = new ArrayList<>();
                for (JsonNode indexNameNode : indicesNode) {
                    String indexName = indexNameNode.asText();
                    log.atInfo()
                        .setMessage("Found index [{}] in snapshot [{}]")
                        .addArgument(indexName)
                        .addArgument(snapshotName)
                        .log();
                    result.add(new SimpleIndex(indexName, snapshotName));
                }
                return result;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SMILE snapshot metadata for snapshot=" + snapshotName, e);
        }
    }

    /**
     * Lookup for the SMILE header sequence 0x3A 0x29 0x0A (":)\n") in the provided bytes.
     * @return the offset of the SMILE header or -1 if not found
     */
    private int findSmileHeaderOffset(byte[] bytes) {
        for (int i = 0; i < bytes.length - 2; i++) {
            if ((bytes[i] & 0xFF) == 0x3A &&
                    (bytes[i + 1] & 0xFF) == 0x29 &&
                    (bytes[i + 2] & 0xFF) == 0x0A) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        for (String name : getRepoData().getSnapshots()) {
            if (name.equals(snapshotName)) {
                return name;
            }
        }
        return null;
    }

    @Override
    public String getIndexId(String indexName) {
        return indexName;
    }

    @Override
    public SourceRepo getRepo() {
        return repo;
    }

    public static class SimpleSnapshot implements SnapshotRepo.Snapshot {
        private final String name;

        public SimpleSnapshot(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getId() {
            return getName();
        }
    }

    public static class SimpleIndex implements SnapshotRepo.Index {
        private final String name;
        private final String snapshotName;

        public SimpleIndex(String name, String snapshotName) {
            this.name = name;
            this.snapshotName = snapshotName;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getId() {
            return getName();
        }

        @Override
        public List<String> getSnapshots() {
            return Collections.singletonList(snapshotName);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
