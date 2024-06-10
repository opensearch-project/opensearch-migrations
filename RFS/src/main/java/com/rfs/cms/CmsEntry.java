package com.rfs.cms;


import com.rfs.common.RfsException;

import lombok.RequiredArgsConstructor;

public class CmsEntry {
    public static enum EntryType {
        SNAPSHOT,
        METADATA,
        INDEX,
        INDEX_WORK_ITEM,
        DOCUMENTS,
        DOCUMENTS_WORK_ITEM
    }

    public abstract static class Base {
        protected Base() {}
        
        // Implementations of this method should provide a string version of the object that fully represents its contents
        public abstract String toRepresentationString();
    
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || !(obj instanceof Base)) {
                return false;
            }
            Base other = (Base) obj;
            return this.toRepresentationString().equals(other.toRepresentationString());
        }
    
        @Override
        public int hashCode() {
            return this.toRepresentationString().hashCode();
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
                throw new CouldNotGenerateNextLeaseDuration("numAttempts=" + numAttempts + " is greater than MAX_ATTEMPTS=" + MAX_ATTEMPTS);
            } else if (numAttempts < 1) {
                throw new CouldNotGenerateNextLeaseDuration("numAttempts=" + numAttempts + " is less than 1");
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
    @RequiredArgsConstructor
    public static class Snapshot extends Base {
        public final EntryType type = EntryType.SNAPSHOT;
        public final String name;
        public final SnapshotStatus status;

        @Override
        public String toRepresentationString() {
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
    @RequiredArgsConstructor
    public static class Metadata extends Leasable {
        public final EntryType type = EntryType.METADATA;
        public final MetadataStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        @Override
        public String toRepresentationString() {
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
    @RequiredArgsConstructor
    public static class Index extends Leasable {
        public final EntryType type = EntryType.INDEX;
        public final IndexStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        @Override
        public String toRepresentationString() {
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
    @RequiredArgsConstructor
    public static class IndexWorkItem extends Base {
        public final EntryType type = EntryType.INDEX_WORK_ITEM;
        public static final int ATTEMPTS_SOFT_LIMIT = 3; // will make at least this many attempts; arbitrarily chosen

        public final String name;
        public final IndexWorkItemStatus status;
        public final Integer numAttempts;
        public final Integer numShards;

        @Override
        public String toRepresentationString() {
            return "IndexWorkItem("
                + "type='" + type.toString() + ","
                + "name=" + name.toString() + ","
                + "status=" + status.toString() + ","
                + "numAttempts=" + numAttempts.toString() + ","
                + "numShards=" + numShards.toString() +
                ")";
        }
    }

    public static enum DocumentsStatus {
        SETUP,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    /*
     * Used to track the progress of migrating all the documents from the soruce cluster
     */
    @RequiredArgsConstructor
    public static class Documents extends Leasable {
        public final EntryType type = EntryType.DOCUMENTS;
        public final DocumentsStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        @Override
        public String toRepresentationString() {
            return "Documents("
                + "type='" + type.toString() + ","
                + "status=" + status.toString() + ","
                + "leaseExpiry=" + leaseExpiry + ","
                + "numAttempts=" + numAttempts.toString() +
                ")";
        }
    }

    public static enum DocumentsWorkItemStatus {
        NOT_STARTED,
        COMPLETED,
        FAILED,
    }

    /*
     * Used to track the migration of a particular index from the source cluster
     */
    @RequiredArgsConstructor
    public static class DocumentsWorkItem extends Leasable {
        public final EntryType type = EntryType.DOCUMENTS_WORK_ITEM;

        public final String indexName;
        public final Integer shardId;
        public final DocumentsWorkItemStatus status;
        public final String leaseExpiry;
        public final Integer numAttempts;

        @Override
        public String toRepresentationString() {
            return "DocumentsWorkItem("
                + "type='" + type.toString() + ","
                + "indexName=" + indexName.toString() + ","
                + "shardId=" + shardId.toString() + ","
                + "status=" + status.toString() + ","
                + "leaseExpiry=" + leaseExpiry.toString() + ","
                + "numAttempts=" + numAttempts.toString() +
                ")";
        }
    }

    public static class CouldNotGenerateNextLeaseDuration extends RfsException {
        public CouldNotGenerateNextLeaseDuration(String message) {
            super("Could not find next lease duration.  Reason: " + message);
        }
    }
}
