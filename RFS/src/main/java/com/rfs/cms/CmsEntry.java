package com.rfs.cms;

import com.rfs.common.RfsException;

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

        public static int getLeaseDurationMs(int numAttempts) {
            if (numAttempts > MAX_ATTEMPTS) {
                throw new CouldNotFindNextLeaseDuration("numAttempts=" + numAttempts + " is greater than MAX_ATTEMPTS=" + MAX_ATTEMPTS);
            } else if (numAttempts < 1) {
                throw new CouldNotFindNextLeaseDuration("numAttempts=" + numAttempts + " is less than 1");
            }
            return METADATA_LEASE_MS * numAttempts; // Arbitratily chosen algorithm
        }

        // TODO: We should be ideally setting the lease expiry using the server's clock, but it's unclear on the best
        // way to do this.  For now, we'll just use the client's clock.
        public static String getLeaseExpiry(long currentTime, int numAttempts) {
            return Long.toString(currentTime + getLeaseDurationMs(numAttempts));
        }

        public final MetadataStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        public Metadata(MetadataStatus status, String leaseExpiry, int numAttempts) {
            this.status = status;
            this.leaseExpiry = leaseExpiry;
            this.numAttempts = numAttempts;
        }
    }

    public static class CouldNotFindNextLeaseDuration extends RfsException {
        public CouldNotFindNextLeaseDuration(String message) {
            super("Could not find next lease duration.  Reason: " + message);
        }
    }
}
