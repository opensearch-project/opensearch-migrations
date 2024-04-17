package com.rfs.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FileSystemSnapshotCreator extends SnapshotCreator {
    private static final Logger logger = LogManager.getLogger(FileSystemSnapshotCreator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConnectionDetails connectionDetails;
    private final String snapshotName;
    private final String snapshotRepoDirectoryPath;

    public FileSystemSnapshotCreator(String snapshotName, ConnectionDetails connectionDetails, String snapshotRepoDirectoryPath) {
        super(snapshotName, connectionDetails);
        this.snapshotName = snapshotName;
        this.connectionDetails = connectionDetails;
        this.snapshotRepoDirectoryPath = snapshotRepoDirectoryPath;
    }

    @Override
    public ObjectNode getRequestBodyForRegisterRepo() {
        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        settings.put("location", snapshotRepoDirectoryPath);

        ObjectNode body = mapper.createObjectNode();
        body.put("type", "fs");
        body.set("settings", settings);
        return body;
    }

}
