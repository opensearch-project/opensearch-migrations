package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

public abstract class SnapshotCreator {
    private static final Logger logger = LogManager.getLogger(SnapshotCreator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final OpenSearchClient client;
    private final IRfsContexts.ICreateSnapshotContext context;
    private final String snapshotName;
    private final List<String> indexAllowlist;

    public SnapshotCreator(String snapshotName, List<String> indexAllowlist, OpenSearchClient client, IRfsContexts.ICreateSnapshotContext context) {
        this.snapshotName = snapshotName;
        this.indexAllowlist = indexAllowlist;
        this.client = client;
        this.context = context;
    }

    abstract ObjectNode getRequestBodyForRegisterRepo();

    public String getRepoName() {
        return "migration_assistant_repo";
    }

    public String getSnapshotName() {
        return snapshotName;
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
            client.registerSnapshotRepo(getRepoName(), settings, context);
            logger.info("Snapshot repo registration successful");
        } catch (Exception e) {
            logger.error("Snapshot repo registration failed", e);
            throw new RepoRegistrationFailed(getRepoName());
        }
    }

    public void createSnapshot() {
        // Assemble the settings
        ObjectNode body = mapper.createObjectNode();
        body.put("indices", this.getIndexAllowlist());
        body.put("ignore_unavailable", true);
        body.put("include_global_state", true);

        // Create the snapshot; idempotent operation
        try {
            client.createSnapshot(getRepoName(), snapshotName, body, context);
            logger.info("Snapshot {} creation initiated", snapshotName);
        } catch (Exception e) {
            logger.error("Snapshot {} creation failed", snapshotName, e);
            throw new SnapshotCreationFailed(snapshotName);
        }
    }

    public boolean isSnapshotFinished() {
        Optional<ObjectNode> response;
        try {
            response = client.getSnapshotStatus(getRepoName(), snapshotName, context);
        } catch (Exception e) {
            logger.error("Failed to get snapshot status", e);
            throw new SnapshotStatusCheckFailed(snapshotName);
        }

        if (response.isEmpty()) {
            logger.error("Snapshot {} does not exist", snapshotName);
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
            logger.error("Snapshot {} has failed with state {}", snapshotName, state);
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
