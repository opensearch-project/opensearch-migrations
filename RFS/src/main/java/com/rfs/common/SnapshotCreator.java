package com.rfs.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.HttpURLConnection;

public abstract class SnapshotCreator {
    private static final Logger logger = LogManager.getLogger(SnapshotCreator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OpenSearchClient client;
    private final String snapshotName;

    public SnapshotCreator(String snapshotName, OpenSearchClient client) {
        this.snapshotName = snapshotName;
        this.client = client;
    }

    abstract ObjectNode getRequestBodyForRegisterRepo();

    public String getRepoName() {
        return "migration_assistant_repo";
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public void registerRepo() {
        ObjectNode settings = getRequestBodyForRegisterRepo();

        // Register the repo; it's fine if it already exists
        RestClient.Response response = client.registerSnapshotRepo(getRepoName(), settings);
        if (response.code == HttpURLConnection.HTTP_OK || response.code == HttpURLConnection.HTTP_CREATED) {
            logger.info("Snapshot repo registration successful");
        } else {
            logger.error("Snapshot repo registration failed");
            throw new RepoRegistrationFailed(getRepoName());
        }
    }

    public void createSnapshot() {
        // Assemble the settings
        ObjectNode body = mapper.createObjectNode();
        body.put("indices", "_all");
        body.put("ignore_unavailable", true);
        body.put("include_global_state", true);

        // Create the snapshot; idempotent operation
        RestClient.Response response = client.createSnapshot(getRepoName(), snapshotName, body);
        if (response.code == HttpURLConnection.HTTP_OK || response.code == HttpURLConnection.HTTP_CREATED) {
            logger.info("Snapshot " + snapshotName + " creation initiated");
        } else {
            logger.error("Snapshot " + snapshotName + " creation failed");
            throw new SnapshotCreationFailed(snapshotName);
        }
    }

    public boolean isSnapshotFinished() {
        RestClient.Response response = client.getSnapshotStatus(getRepoName(), snapshotName);
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            logger.error("Snapshot " + snapshotName + " does not exist");
            throw new SnapshotDoesNotExist(snapshotName);
        }

        JsonNode responseJson;
        try {
            responseJson = mapper.readTree(response.body);
        } catch (Exception e) {
            logger.error("Failed to parse snapshot status response", e);
            throw new SnapshotStatusUnparsable(snapshotName);
        }
        JsonNode firstSnapshot = responseJson.path("snapshots").get(0);
        JsonNode stateNode = firstSnapshot.path("state");
        String state = stateNode.asText();        

        if (state.equals("SUCCESS")) {
            return true;
        } else if (state.equals("IN_PROGRESS")) {
            return false;
        } else {
            logger.error("Snapshot " + snapshotName + " has failed with state " + state);
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

    public static class SnapshotStatusUnparsable extends RfsException {
        public SnapshotStatusUnparsable(String snapshotName) {
            super("Status of Snapshot " + snapshotName + " is not parsable");
        }
    }
}
