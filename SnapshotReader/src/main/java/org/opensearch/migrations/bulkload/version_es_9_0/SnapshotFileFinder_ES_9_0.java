package org.opensearch.migrations.bulkload.version_es_9_0;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * SnapshotFileFinder for ES 9.x / OS 3.x. These versions introduced a new shard
 * layout known as "shard_path_type" with three values:
 *
 *   0 = FIXED         -- classic "indices/{indexUUID}/{shardId}/..." (same as ES 5-8, OS 1/2)
 *   1 = HASHED_PREFIX -- "{hashedPrefix}/indices/{indexUUID}/{shardId}/..."
 *   2 = HASHED_INFIX  -- "indices/{hashedPrefix}/{indexUUID}/{shardId}/..."
 *
 * For HASHED_PREFIX / HASHED_INFIX, the repository writes a companion file at
 * "snapshot_shard_paths/snapshot_path_{indexId}.{indexName}.{numShards}.{pathType}.{hashAlgo}"
 * whose JSON body contains the authoritative list of shard paths. We resolve the
 * actual on-disk directory by reading that file, which avoids having to re-implement
 * OpenSearch's FNV1a hashing scheme.
 *
 * When no such file exists (plain / FIXED layout, e.g. upgraded old repos), we fall
 * back to the classic BaseSnapshotFileFinder behavior.
 *
 * <p><b>Authoritative format spec</b> — the on-disk layout, the 0/1/2 enum values, and
 * the FNV1a-1 hashing scheme are defined by OpenSearch's {@code RemoteStoreEnums}:
 *
 * <ul>
 *   <li>{@code org.opensearch.index.remote.RemoteStoreEnums.PathType} — the FIXED /
 *       HASHED_PREFIX / HASHED_INFIX enum with the path-assembly logic, at
 *       <a href="https://github.com/opensearch-project/OpenSearch/blob/main/server/src/main/java/org/opensearch/index/remote/RemoteStoreEnums.java">OpenSearch/server/.../RemoteStoreEnums.java</a>.</li>
 *   <li>{@code RemoteStoreEnums.PathHashAlgorithm} — the hash algorithms (FNV_1A_BASE64,
 *       FNV_1A_COMPOSITE_1) whose identifier is the {@code hashAlgo} segment in the
 *       {@code snapshot_path_*} filename.</li>
 *   <li>OpenSearch RFC for the remote-backed storage path scheme:
 *       <a href="https://github.com/opensearch-project/OpenSearch/issues/12567">opensearch-project/OpenSearch#12567</a>.</li>
 * </ul>
 */
@Slf4j
public class SnapshotFileFinder_ES_9_0 extends BaseSnapshotFileFinder {

    public static final String SHARD_PATHS_DIR = "snapshot_shard_paths";
    public static final String SHARD_PATH_PREFIX = "snapshot_path_";

    private final SourceRepo repo;

    public SnapshotFileFinder_ES_9_0() {
        this(null);
    }

    public SnapshotFileFinder_ES_9_0(SourceRepo repo) {
        this.repo = repo;
    }

    @Override
    public Path getShardDirPath(Path root, String indexUUID, int shardId) {
        Path resolved = resolveFromShardPathsFile(root, indexUUID, shardId);
        if (resolved != null) {
            return resolved;
        }
        return super.getShardDirPath(root, indexUUID, shardId);
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId) {
        return getShardDirPath(root, indexUUID, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName) {
        return getShardDirPath(root, indexUUID, shardId).resolve(blobName);
    }

    /**
     * Attempts to look up the shard directory via the "snapshot_shard_paths" companion
     * file. Returns null if no such file is present (meaning the repo uses the classic
     * FIXED layout and the default BaseSnapshotFileFinder resolution applies).
     */
    protected Path resolveFromShardPathsFile(Path root, String indexUUID, int shardId) {
        Path shardPathsDir = root.resolve(SHARD_PATHS_DIR);
        if (repo instanceof org.opensearch.migrations.bulkload.common.S3Repo && !Files.isDirectory(shardPathsDir)) {
            // For S3-backed repos, the shard paths directory is only populated on demand.
            // Fetch the entire directory once; it's small (one file per index per snapshot).
            try {
                ((org.opensearch.migrations.bulkload.common.S3Repo) repo).downloadPrefix(SHARD_PATHS_DIR + "/");
            } catch (RuntimeException e) {
                log.atDebug()
                    .setMessage("No snapshot_shard_paths/ prefix in S3 repo (classic FIXED layout?): {}")
                    .addArgument(e.getMessage())
                    .log();
            }
        }
        if (!Files.isDirectory(shardPathsDir)) {
            return null;
        }

        String prefix = SHARD_PATH_PREFIX + indexUUID + ".";
        // At most one snapshot_path_<indexId>.* file per index per repo; take the first match.
        Path matching = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shardPathsDir, prefix + "*")) {
            Iterator<Path> it = stream.iterator();
            if (it.hasNext()) {
                matching = it.next();
            }
        } catch (IOException e) {
            log.atDebug()
                .setMessage("Failed to list {} looking for {}: {}")
                .addArgument(shardPathsDir)
                .addArgument(prefix)
                .addArgument(e.getMessage())
                .log();
            return null;
        }
        if (matching == null) {
            return null;
        }

        try {
            ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
            ShardPathsFile data = mapper.readValue(matching.toFile(), ShardPathsFile.class);
            List<String> paths = data.paths != null ? data.paths : new ArrayList<>();
            if (shardId < 0 || shardId >= paths.size()) {
                log.atWarn()
                    .setMessage("Shard id {} out of range [0,{}) in {}, falling back to classic layout")
                    .addArgument(shardId)
                    .addArgument(paths.size())
                    .addArgument(matching)
                    .log();
                return null;
            }
            String rel = paths.get(shardId);
            if (rel == null || rel.isEmpty()) {
                return null;
            }
            // Strip trailing slash
            if (rel.endsWith("/")) {
                rel = rel.substring(0, rel.length() - 1);
            }
            return root.resolve(rel);
        } catch (IOException e) {
            log.atWarn()
                .setMessage("Failed to parse shard paths file {}: {} -- falling back to classic layout")
                .addArgument(matching)
                .addArgument(e.getMessage())
                .log();
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ShardPathsFile {
        @JsonProperty("indexId")
        public String indexId;
        @JsonProperty("indexName")
        public String indexName;
        @JsonProperty("number_of_shards")
        public Integer numberOfShards;
        @JsonProperty("shard_path_type")
        public Integer shardPathType;
        @JsonProperty("shard_path_hash_algorithm")
        public Integer shardPathHashAlgorithm;
        @JsonProperty("paths")
        public List<String> paths;
    }
}
