package com.rfs.common;

import java.net.HttpURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class S3SnapshotCreator {
    private static final Logger logger = LogManager.getLogger(S3SnapshotCreator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConnectionDetails connectionDetails;
    private final String snapshotName;
    private final String s3Uri;
    private final String s3Region;

    public S3SnapshotCreator(String snapshotName, ConnectionDetails connectionDetails, String s3Uri, String s3Region) {
        this.snapshotName = snapshotName;
        this.connectionDetails = connectionDetails;
        this.s3Uri = s3Uri;
        this.s3Region = s3Region;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    /*
     * Extracts the bucket name from the S3 URI
     * s3://my-bucket-name/my-folder/my-nested-folder => my-bucket-name
     */
    public String getBucketName() {
        return s3Uri.split("/")[2];
    }

    /*
     * Extracts the base path from the S3 URI; could be nested arbitrarily deep
     * s3://my-bucket-name/my-folder/my-nested-folder => my-folder/my-nested-folder
     */
    public String getBasePath() {
        int thirdSlashIndex = s3Uri.indexOf('/', 5);
        if (thirdSlashIndex == -1) {
            // Nothing after the bucket name
            return null;
        }

        // Extract everything after the third "/", excluding any final "/"
        String rawBasePath = s3Uri.substring(thirdSlashIndex + 1);
        if (rawBasePath.endsWith("/")) {
            return rawBasePath.substring(0, rawBasePath.length() - 1);
        } else {
            return rawBasePath;
        }
    }

    public void registerRepo() throws Exception {
        // Assemble the REST path
        String targetName = "_snapshot/s3_repo";

        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        settings.put("bucket", getBucketName());
        settings.put("region", s3Region);
        settings.put("base_path", getBasePath());

        ObjectNode body = mapper.createObjectNode();
        body.put("type", "s3");
        body.set("settings", settings);

        // Register the repo; it's fine if it already exists
        RestClient client = new RestClient(connectionDetails);
        String bodyString = body.toString();
        RestClient.Response response = client.put(targetName, bodyString, false);
        if (response.code == HttpURLConnection.HTTP_OK) {
            logger.info("S3 Repo registration successful");
        } else {
            logger.error("S3 Repo registration failed");
            throw new RepoRegistrationFailed("s3_repo");
        }
    }

    public void createSnapshot() throws Exception {
        // Assemble the REST path
        String targetName = "_snapshot/s3_repo/" + getSnapshotName();

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.put("indices", "_all");
        body.put("ignore_unavailable", true);
        body.put("include_global_state", true);

        // Register the repo; it's fine if it already exists
        RestClient client = new RestClient(connectionDetails);
        String bodyString = body.toString();
        RestClient.Response response = client.put(targetName, bodyString, false);
        if (response.code == HttpURLConnection.HTTP_OK) {
            logger.info("Snapshot " + getSnapshotName() + " creation successful");
        } else {
            logger.error("Snapshot " + getSnapshotName() + " creation failed");
            throw new SnapshotCreationFailed(getSnapshotName());
        }
    }

    public boolean isSnapshotFinished() throws Exception {
        // Assemble the REST path
        String targetName = "_snapshot/s3_repo/" + getSnapshotName();

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
            logger.error("Snapshot " + getSnapshotName() + " has failed");
            throw new SnapshotCreationFailed(getSnapshotName());
        }
    }

    public class RepoRegistrationFailed extends Exception {
        public RepoRegistrationFailed(String repoName) {
            super("Failed to register S3 repo " + repoName);
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
