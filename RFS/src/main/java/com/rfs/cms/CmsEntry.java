package com.rfs.cms;


import com.rfs.common.RfsException;

public class CmsEntry {
    public static enum EntryType {
        SNAPSHOT,
        METADATA,
        INDEX,
        INDEX_WORK_ITEM,
    }

    public abstract static class Base {
        protected Base() {}
        
        @Override
        public abstract String toString();
    
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || !(obj instanceof Base)) {
                return false;
            }
            Base other = (Base) obj;
            return this.toString().equals(other.toString());
        }
    
        @Override
        public int hashCode() {
            return this.toString().hashCode();
        }
    }

    /*
     * Provides a base class for leasable entry types.  Doesn't allow for customization of lease mechanics, but it's 
     * unclear how to achieve that in Java given the constraints around static methods.
    */
    public abstract static class Leasable extends Base {
        public static final int LEASE_MS = 1 * 60 * 1000; // 1 minute, arbitrarily chosen
        public static final int MAX_ATTEMPTS = 3; // arbitrarily chosen

        protected Leasable() {}

        public static int getLeaseDurationMs(int numAttempts) {
            if (numAttempts > MAX_ATTEMPTS) {
                throw new CouldNotFindNextLeaseDuration("numAttempts=" + numAttempts + " is greater than MAX_ATTEMPTS=" + MAX_ATTEMPTS);
            } else if (numAttempts < 1) {
                throw new CouldNotFindNextLeaseDuration("numAttempts=" + numAttempts + " is less than 1");
            }
            return LEASE_MS * numAttempts; // Arbitratily chosen algorithm
        }

        // TODO: We should be ideally setting the lease expiry using the server's clock, but it's unclear on the best
        // way to do this.  For now, we'll just use the client's clock.
        public static String getLeaseExpiry(long currentTime, int numAttempts) {
            return Long.toString(currentTime + getLeaseDurationMs(numAttempts));
        }
    }

    public static enum SnapshotStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    /*
     * Used to track the progress of taking a snapshot of the source cluster
     */
    public static class Snapshot extends Base {
        public final EntryType type = EntryType.SNAPSHOT;
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
                + "type='" + type.toString() + ","
                + "name='" + name + ","
                + "status=" + status.toString() +
                ")";
        }
    }

    public static enum MetadataStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    /*
     * Used to track the progress of migrating all the templates from the source cluster
     */
    public static class Metadata extends Leasable {
        public final EntryType type = EntryType.METADATA;
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
                + "type='" + type.toString() + ","
                + "status=" + status.toString() + ","
                + "leaseExpiry=" + leaseExpiry + ","
                + "numAttempts=" + numAttempts.toString() +
                ")";
        }
    }

    public static enum IndexStatus {
        SETUP,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    /*
     * Used to track the progress of migrating all the indices from the soruce cluster
     */
    public static class Index extends Leasable {
        public final EntryType type = EntryType.INDEX;
        public final IndexStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        public Index(IndexStatus status, String leaseExpiry, int numAttempts) {
            super();
            this.status = status;
            this.leaseExpiry = leaseExpiry;
            this.numAttempts = numAttempts;
        }

        @Override
        public String toString() {
            return "Index("
                + "type='" + type.toString() + ","
                + "status=" + status.toString() + ","
                + "leaseExpiry=" + leaseExpiry + ","
                + "numAttempts=" + numAttempts.toString() +
                ")";
        }
    }

    public static enum IndexWorkItemStatus {
        NOT_STARTED,
        COMPLETED,
        FAILED,
    }

    /*
     * Used to track the migration of a particular index from the source cluster
     */
    public static class IndexWorkItem extends Base {
        public final EntryType type = EntryType.INDEX_WORK_ITEM;
        public static final int ATTEMPTS_SOFT_LIMIT = 3; // will make at least this many attempts; arbitrarily chosen

        public final String name;
        public final IndexWorkItemStatus status;
        public final Integer numAttempts;
        public final Integer numShards;

        public IndexWorkItem(String name, IndexWorkItemStatus status, int numAttempts, int numShards) {
            super();
            this.name = name;
            this.status = status;
            this.numAttempts = numAttempts;
            this.numShards = numShards;
        }

        @Override
        public String toString() {
            return "IndexWorkItem("
                + "type='" + type.toString() + ","
                + "name=" + name.toString() + ","
                + "status=" + status.toString() + ","
                + "numAttempts=" + numAttempts.toString() + ","
                + "numShards=" + numShards.toString() +
                ")";
        }
    }

    public static class CouldNotFindNextLeaseDuration extends RfsException {
        public CouldNotFindNextLeaseDuration(String message) {
            super("Could not find next lease duration.  Reason: " + message);
        }
    }
}
