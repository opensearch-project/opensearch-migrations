package com.rfs.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.tracing.IRfsContexts;

public class FileSystemSnapshotCreator extends SnapshotCreator {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OpenSearchClient client;
    private final String snapshotName;
    private final String snapshotRepoDirectoryPath;

    public FileSystemSnapshotCreator(String snapshotName, OpenSearchClient client, String snapshotRepoDirectoryPath,
                                     IRfsContexts.ICreateSnapshotContext context) {
        super(snapshotName, client, context);
        this.snapshotName = snapshotName;
        this.client = client;
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
