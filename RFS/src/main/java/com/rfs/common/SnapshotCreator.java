package com.rfs.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.HttpURLConnection;

public abstract class SnapshotCreator {
    private static final Logger logger = LogManager.getLogger(SnapshotCreator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OpenSearchClient client;
    @Getter
    private final String snapshotName;

    public SnapshotCreator(String snapshotName, OpenSearchClient client) {
        this.snapshotName = snapshotName;
        this.client = client;
    }

    abstract ObjectNode getRequestBodyForRegisterRepo();

    public String getRepoName() {
        return "migration_assistant_repo";
    }

    public void registerRepo() throws Exception {
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

    public void createSnapshot() throws Exception {
        // Assemble the settings
        ObjectNode body = mapper.createObjectNode();
        body.put("indices", "_all");
        body.put("ignore_unavailable", true);
        body.put("include_global_state", true);

        // Register the repo; idempotent operation
        RestClient.Response response = client.createSnapshot(getRepoName(), getSnapshotName(), body);
        if (response.code == HttpURLConnection.HTTP_OK || response.code == HttpURLConnection.HTTP_CREATED) {
            logger.info("Snapshot " + getSnapshotName() + " creation initiated");
        } else {
            logger.error("Snapshot " + getSnapshotName() + " creation failed");
            throw new SnapshotCreationFailed(getSnapshotName());
        }
    }

    public boolean isSnapshotFinished() throws Exception {
        // Check if the snapshot has finished
        RestClient.Response response = client.getSnapshotStatus(getRepoName(), getSnapshotName());
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            logger.error("Snapshot " + getSnapshotName() + " does not exist");
            throw new SnapshotDoesNotExist(getSnapshotName());
        }
        JsonNode responseJson = mapper.readTree(response.body);
        JsonNode firstSnapshot = responseJson.path("snapshots").get(0);
        JsonNode stateNode = firstSnapshot.path("state");
        String state = stateNode.asText();

        if (state.equals("SUCCESS")) {
            return true;
        } else if (state.equals("IN_PROGRESS")) {
            return false;
        } else {
            logger.error("Snapshot " + getSnapshotName() + " has failed with state " + state);
            throw new SnapshotCreationFailed(getSnapshotName());
        }
    }

    public class RepoRegistrationFailed extends Exception {
        public RepoRegistrationFailed(String repoName) {
            super("Failed to register repo " + repoName);
        }
    }

    public class SnapshotCreationFailed extends Exception {
        public SnapshotCreationFailed(String snapshotName) {
            super("Failed to create snapshot " + snapshotName);
        }
    }

    public class SnapshotDoesNotExist extends Exception {
        public SnapshotDoesNotExist(String snapshotName) {
            super("Snapshot " + snapshotName + " does not exist");
        }
    }
}
