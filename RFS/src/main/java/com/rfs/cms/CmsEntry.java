package com.rfs.cms;


public class CmsEntry {
    public static enum SnapshotStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    public static class Snapshot {
        public final String name;
        public final SnapshotStatus status;

        public Snapshot(String name, SnapshotStatus status) {
            this.name = name;
            this.status = status;
        }
    }

    public static enum MetadataStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    public static class Metadata {
        public static final int METADATA_LEASE_MS = 1 * 60 * 1000; // 1 minute, arbitrarily chosen
        public static final int MAX_ATTEMPTS = 3; // arbitrarily chosen

        public final MetadataStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        public Metadata(MetadataStatus status, String leaseExpiry, int numAttempts) {
            this.status = status;
            this.leaseExpiry = leaseExpiry;
            this.numAttempts = numAttempts;
        }
    }
}
