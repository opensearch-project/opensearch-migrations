package org.opensearch.migrations.bulkload.common;

import java.util.List;

import lombok.NonNull;

public class SnapshotRepo {
    private SnapshotRepo() {}

    /**
     * Defines the behavior required to surface a snapshot repo's metadata
     */
    public static interface Provider {
        // Returns a list of all snapshots in the snapshot repository
        public List<SnapshotRepo.Snapshot> getSnapshots();

        // Returns a list of all indices in the specified snapshot
        public List<? extends SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName);

        // Get the ID of a snapshot from its name
        public String getSnapshotId(String snapshotName);

        // Get the ID of an index from its name
        public String getIndexId(String indexName);

        // Get the underlying repo
        public SourceRepo getRepo();

    }

    /**
     * Defines the behavior required to surface the details of a snapshot in a snapshot repo's metadata
     */
    public static interface Snapshot {
        String getName();

        String getId();

        default boolean isNameOrIdEqual(@NonNull Snapshot other) {
            var name = getName();
            var id = getId();
            return ((name != null) && name.equals(other.getName())) ||
                    ((id != null) && id.equals(other.getId()));
        }
    }

    /**
     * Defines the behavior required to surface the details of a index in a snapshot repo's metadata
     */
    public static interface Index {
        String getName();

        String getId();
    }

    public static class CannotParseRepoFile extends RfsException {
        public CannotParseRepoFile(SourceRepo repo) {
            super("Unable to locate valid snapshot repository files in repo: " + repo);
        }

        public CannotParseRepoFile(String message) {
            super(message);
        }

        public CannotParseRepoFile(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
