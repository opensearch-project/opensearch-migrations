package org.opensearch.migrations.bulkload.common;

import java.util.List;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class FileSystemSnapshotCreator extends SnapshotCreator {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String snapshotRepoDirectoryPath;

    public FileSystemSnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        String snapshotRepoDirectoryPath,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        this(snapshotName, snapshotRepoName, client, snapshotRepoDirectoryPath, indexAllowlist, context, false, true);
    }

    public FileSystemSnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        String snapshotRepoDirectoryPath,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context,
        boolean compressionEnabled,
        boolean includeGlobalState
    ) {
        super(snapshotName, snapshotRepoName, indexAllowlist, client, context, compressionEnabled, includeGlobalState);
        this.snapshotRepoDirectoryPath = snapshotRepoDirectoryPath;
    }

    @Override
    public ObjectNode getRequestBodyForRegisterRepo() {
        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        settings.put("location", snapshotRepoDirectoryPath);
        settings.put("compress", compressionEnabled);
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "fs");
        body.set("settings", settings);
        return body;
    }

}
