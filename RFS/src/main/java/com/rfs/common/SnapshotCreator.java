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

    private final ConnectionDetails connectionDetails;
    @Getter
    private final String snapshotName;

    public SnapshotCreator(String snapshotName, ConnectionDetails connectionDetails) {
        this.snapshotName = snapshotName;
        this.connectionDetails = connectionDetails;
    }

    abstract ObjectNode getRequestBodyForRegisterRepo();

    public String getRepoName() {
        return "migration_assistant_repo";
    }

    public void registerRepo() throws Exception {
        // Assemble the REST path
        String targetName = "_snapshot/" + getRepoName();

        ObjectNode body = getRequestBodyForRegisterRepo();

        // Register the repo; it's fine if it already exists
        RestClient client = new RestClient(connectionDetails);
        String bodyString = body.toString();
        RestClient.Response response = client.put(targetName, bodyString, false);
        if (response.code == HttpURLConnection.HTTP_OK || response.code == HttpURLConnection.HTTP_CREATED) {
            logger.info("Snapshot repo registration successful");
        } else {
            logger.error("Snapshot repo registration failed");
            throw new RepoRegistrationFailed(getRepoName());
        }
    }

    public void createSnapshot() throws Exception {
        // Assemble the REST path
        String targetName = "_snapshot/" + getRepoName() + "/" + getSnapshotName();

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.put("indices", "_all");
        body.put("ignore_unavailable", true);
        body.put("include_global_state", true);

        // Register the repo; idempotent operation
        RestClient client = new RestClient(connectionDetails);
        String bodyString = body.toString();
        RestClient.Response response = client.put(targetName, bodyString, false);
        if (response.code == HttpURLConnection.HTTP_OK || response.code == HttpURLConnection.HTTP_CREATED) {
            logger.info("Snapshot " + getSnapshotName() + " creation initiated");
        } else {
            logger.error("Snapshot " + getSnapshotName() + " creation failed");
            throw new SnapshotCreationFailed(getSnapshotName());
        }
    }

    public boolean isSnapshotFinished() throws Exception {
        // Assemble the REST path
        String targetName = "_snapshot/" + getRepoName() + "/" + getSnapshotName();

        // Check if the snapshot has finished
        RestClient client = new RestClient(connectionDetails);
        RestClient.Response response = client.get(targetName, false);
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
