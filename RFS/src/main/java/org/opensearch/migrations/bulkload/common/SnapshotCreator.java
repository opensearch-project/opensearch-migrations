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
                putBucketSettings(settings, s.s3Uri().bucketName, s.s3Uri().key);
                settings.put("region", region);
                if (roleArn != null) {
                    settings.put("role_arn", roleArn);
                }
                if (endpoint != null) {
                    settings.put("endpoint", endpoint);
                }
                body.put("type", "s3");
            }
            case RepoUri.GcsRepoUri g -> {
                var gcsUri = new GcsUri(g.rawUri());
                putBucketSettings(settings, gcsUri.bucketName, gcsUri.key);
                // Unlike repository-s3, repository-gcs has no repository-level "endpoint"
                // setting; a custom endpoint (e.g. fake-gcs-server for testing) must be
                // configured cluster-side via gcs.client.<client>.endpoint. Setting it here
                // would be silently ignored, so the endpoint is intentionally not forwarded.
                body.put("type", "gcs");
            }
            case RepoUri.AzureRepoUri a -> {
                var azureUri = a.azureUri();
                // repository-azure calls its bucket "container", hence the sibling helper.
                putContainerSettings(settings, azureUri.containerName, azureUri.key);
                // Same rule as the repository-gcs arm above: repository-azure exposes no
                // repository-level "endpoint" setting, so an emulator endpoint (Azurite)
                // cannot be forwarded here and is intentionally dropped.
                //
                // The storage account is intentionally not forwarded either. In
                // repository-azure the "client" setting falls back to the deprecated
                // "account" setting, and both name a keystore client alias resolved as
                // azure.client.<client>.account, not a storage account name. Emitting
                // azureUri.accountName in either field would name a client that does not
                // exist on the cluster. The account segment of the URI serves the reader
                // side (AzureRepo); a writing cluster resolves its account from its own
                // keystore.
                body.put("type", "azure");
            }
        }

        body.set("settings", settings);
        return body;
    }

    /** repository-s3 and repository-gcs both key the container by "bucket". */
    private void putBucketSettings(ObjectNode settings, String bucket, String key) {
        settings.put("bucket", bucket);
        putCommonBlobSettings(settings, key);
    }

    /** repository-azure keys the container by "container", so it cannot share the above. */
    private void putContainerSettings(ObjectNode settings, String container, String key) {
        settings.put("container", container);
        putCommonBlobSettings(settings, key);
    }

    /**
     * Settings every blob-store repository type spells identically. Deliberately does NOT take
     * the container-name key as a parameter: keeping "bucket" and "container" as literals in the
     * two named methods above means the compiler and the reader both know which provider a call
     * is for, where a key-name argument would be an unchecked magic string at each call site.
     */
    private void putCommonBlobSettings(ObjectNode settings, String basePath) {
        if (basePath != null && !basePath.isEmpty()) {
            settings.put("base_path", basePath);
        }
        settings.put("compress", compressionEnabled);
        if (maxSnapshotRateMBPerNode != null) {
            settings.put("max_snapshot_bytes_per_sec", maxSnapshotRateMBPerNode + "mb");
        }
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
