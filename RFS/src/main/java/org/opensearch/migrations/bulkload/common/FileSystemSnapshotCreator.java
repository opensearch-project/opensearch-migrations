package org.opensearch.migrations.bulkload.common;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

public class FileSystemSnapshotCreator extends SnapshotCreator {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String snapshotRepoDirectoryPath;

    public FileSystemSnapshotCreator(
        String snapshotName,
        OpenSearchClient client,
        String snapshotRepoDirectoryPath,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        super(snapshotName, indexAllowlist, client, context);
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
