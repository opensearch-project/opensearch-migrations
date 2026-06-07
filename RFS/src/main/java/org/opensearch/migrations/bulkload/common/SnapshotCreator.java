package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnapshotCreator {
    private static final ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();

    private final OpenSearchClient client;
    private final IRfsContexts.ICreateSnapshotContext context;
    @Getter
    private final String snapshotName;
    @Getter
    private final String snapshotRepoName;
    private final List<String> indexAllowlist;
    private final boolean compressionEnabled;
    private final boolean includeGlobalState;
    private final RepoUri repoUri;
    private final String region;
    private final String endpoint;
    private final Integer maxSnapshotRateMBPerNode;
    private final String roleArn;

    public SnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        RepoUri repoUri,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        this(snapshotName, snapshotRepoName, client, repoUri, indexAllowlist, context, false, true, null, null, null, null);
    }

    public SnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        RepoUri repoUri,
        String region,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        this(snapshotName, snapshotRepoName, client, repoUri, indexAllowlist, context, false, true, region, null, null, null);
    }

    public SnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        RepoUri repoUri,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context,
        boolean compressionEnabled,
        boolean includeGlobalState,
        String region,
        String endpoint,
        Integer maxSnapshotRateMBPerNode,
        String roleArn
    ) {
        this.snapshotName = snapshotName;
        this.snapshotRepoName = snapshotRepoName;
        this.indexAllowlist = indexAllowlist;
        this.client = client;
        this.context = context;
        this.compressionEnabled = compressionEnabled;
        this.includeGlobalState = includeGlobalState;
        this.repoUri = repoUri;
        this.region = region;
        this.endpoint = endpoint;
        this.maxSnapshotRateMBPerNode = maxSnapshotRateMBPerNode;
        this.roleArn = roleArn;
    }

    public ObjectNode getRequestBodyForRegisterRepo() {
        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        ObjectNode body = mapper.createObjectNode();

        switch (repoUri) {
            case RepoUri.FileRepoUri f -> {
                settings.put("location", f.path());
                settings.put("compress", compressionEnabled);
                body.put("type", "fs");
            }
            case RepoUri.S3RepoUri s -> {
                settings.put("bucket", s.s3Uri().bucketName);
                settings.put("region", region);
                if (!s.s3Uri().key.isEmpty()) {
                    settings.put("base_path", s.s3Uri().key);
                }
                settings.put("compress", compressionEnabled);
                if (roleArn != null) {
                    settings.put("role_arn", roleArn);
                }
                if (endpoint != null) {
                    settings.put("endpoint", endpoint);
                }
                if (maxSnapshotRateMBPerNode != null) {
                    settings.put("max_snapshot_bytes_per_sec", maxSnapshotRateMBPerNode + "mb");
                }
                body.put("type", "s3");
            }
            case RepoUri.GcsRepoUri g -> {
                // Parse gs://bucket/path manually (GcsUri is in a downstream module)
                String raw = g.rawUri().substring(5); // strip "gs://"
                String[] parts = raw.split("/", 2);
                settings.put("bucket", parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    String basePath = parts[1].endsWith("/") ? parts[1].substring(0, parts[1].length() - 1) : parts[1];
                    settings.put("base_path", basePath);
                }
                settings.put("compress", compressionEnabled);
                if (maxSnapshotRateMBPerNode != null) {
                    settings.put("max_snapshot_bytes_per_sec", maxSnapshotRateMBPerNode + "mb");
                }
                body.put("type", "gcs");
            }
        }

        body.set("settings", settings);
        return body;
    }

    public String getIndexAllowlist() {
        if (this.indexAllowlist == null || this.indexAllowlist.isEmpty()) {
            return "_all";
        } else {
            return String.join(",", this.indexAllowlist);
        }
    }

    public void registerRepo() {
        ObjectNode settings = getRequestBodyForRegisterRepo();

        // Register the repo; it's fine if it already exists
        try {
            client.registerSnapshotRepo(getSnapshotRepoName(), settings, context);
            log.atInfo().setMessage("Snapshot repo registration successful").log();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Snapshot repo registration failed").log();
            throw new RepoRegistrationFailed(getSnapshotRepoName());
        }
    }

    public void createSnapshot() {
        // Assemble the settings
        ObjectNode body = mapper.createObjectNode();
        body.put("indices", this.getIndexAllowlist());
        body.put("ignore_unavailable", true);
        body.put("include_global_state", includeGlobalState);

        // Create the snapshot; idempotent operation
        try {
            client.createSnapshot(getSnapshotRepoName(), snapshotName, body, context);
            log.atInfo().setMessage("Snapshot {} creation initiated").addArgument(snapshotName).log();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Snapshot {} creation failed").addArgument(snapshotName).log();
            throw new SnapshotCreationFailed(snapshotName);
        }
    }

    public boolean isSnapshotFinished() {
        Optional<ObjectNode> response;
        try {
            response = client.getSnapshotStatus(getSnapshotRepoName(), snapshotName, context);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Failed to get snapshot status").log();
            throw new SnapshotStatusCheckFailed(snapshotName);
        }

        if (response.isEmpty()) {
            log.atError().setMessage("Snapshot {} does not exist").addArgument(snapshotName).log();
            throw new SnapshotDoesNotExist(snapshotName);
        }

        JsonNode responseJson = response.get();
        JsonNode firstSnapshot = responseJson.path("snapshots").get(0);
        JsonNode stateNode = firstSnapshot.path("state");
        String state = stateNode.asText();

        if (state.equals("SUCCESS")) {
            return true;
        } else if (state.equals("IN_PROGRESS")) {
            return false;
        } else {
            log.atError().setMessage("Snapshot {} has failed with state {}")
                    .addArgument(snapshotName)
                    .addArgument(state).log();
            throw new SnapshotCreationFailed(snapshotName);
        }
    }

    public static class RepoRegistrationFailed extends RfsException {
        public RepoRegistrationFailed(String repoName) {
            super("Failed to register repo " + repoName);
        }
    }

    public static class SnapshotCreationFailed extends RfsException {
        public SnapshotCreationFailed(String snapshotName) {
            super("Failed to create snapshot " + snapshotName);
        }
    }

    public static class SnapshotDoesNotExist extends RfsException {
        public SnapshotDoesNotExist(String snapshotName) {
            super("Snapshot " + snapshotName + " does not exist");
        }
    }

    public static class SnapshotStatusCheckFailed extends RfsException {
        public SnapshotStatusCheckFailed(String snapshotName) {
            super("We were unable to retrieve the status of Snapshot " + snapshotName);
        }
    }
}
