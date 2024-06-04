package com.rfs.cms;

import com.rfs.common.RfsException;

public class CmsEntry {
    public abstract static class Base {
        protected Base() {}
        public abstract String toString();
    }

    public static enum SnapshotStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    public static class Snapshot extends Base {
        public final String name;
        public final SnapshotStatus status;

        public Snapshot(String name, SnapshotStatus status) {
            super();
            this.name = name;
            this.status = status;
        }

        @Override
        public String toString() {
            return "Snapshot("
                + "name='" + name + ","
                + "status=" + status +
                ")";
        }
    }

    public static enum MetadataStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    public static class Metadata extends Base {
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
            super();
            this.status = status;
            this.leaseExpiry = leaseExpiry;
            this.numAttempts = numAttempts;
        }

        @Override
        public String toString() {
            return "Metadata("
                + "status=" + status.toString() + ","
                + "leaseExpiry=" + leaseExpiry + ","
                + "numAttempts=" + numAttempts.toString() +
                ")";
        }
    }

    public static class CouldNotFindNextLeaseDuration extends RfsException {
        public CouldNotFindNextLeaseDuration(String message) {
            super("Could not find next lease duration.  Reason: " + message);
        }
    }
}
