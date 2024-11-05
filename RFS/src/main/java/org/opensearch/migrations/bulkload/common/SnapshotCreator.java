package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class SnapshotCreator {
    private static final ObjectMapper mapper = new ObjectMapper();
    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final OpenSearchClient client;
    private final IRfsContexts.ICreateSnapshotContext context;
    @Getter
    private final String snapshotName;
    private final List<String> indexAllowlist;

    protected SnapshotCreator(String snapshotName, List<String> indexAllowlist, OpenSearchClient client,
            IRfsContexts.ICreateSnapshotContext context) {
        this.snapshotName = snapshotName;
        this.indexAllowlist = indexAllowlist;
        this.client = client;
        this.context = context;
    }

    abstract ObjectNode getRequestBodyForRegisterRepo();

    public String getRepoName() {
        return "migration_assistant_repo";
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
            log.atInfo().setMessage("Snapshot repo registration successful").log();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Snapshot repo registration failed").log();
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
            log.atInfo().setMessage("Snapshot {} creation initiated").addArgument(snapshotName).log();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Snapshot {} creation failed").addArgument(snapshotName).log();
            throw new SnapshotCreationFailed(snapshotName);
        }
    }

    public boolean isSnapshotFinished() {
        Optional<ObjectNode> response;
        try {
            response = client.getSnapshotStatus(getRepoName(), snapshotName, context);
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
