package com.rfs.cms;

/*
 * Client to connect to and work with the Coordinating Metadata Store.  The CMS could be implemented by any reasonable
 * data store option (Postgres, AWS DynamoDB, Elasticsearch/Opensearch, etc).
 */
public interface CmsClient {
    /*
     * Creates a new entry in the CMS for the Snapshot's progress.  Returns true if we created the entry, and false if
     * the entry already exists.
     */
    public boolean createSnapshotEntry(String snapshotName);

    /*
     * Attempt to retrieve the Snapshot entry from the CMS, if it exists; null if it doesn't
     */
    public CmsEntry.Snapshot getSnapshotEntry(String snapshotName);

    /*
     * Updates the status of the Snapshot entry in the CMS.  Returns true if the update was successful, and false if
     * something else updated it before we could
     */
    public boolean updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status);

    /*
     * Creates a new entry in the CMS for the Metadata Migration's progress.  Returns true if we created the entry, and
     * false if the entry already exists.
     */
    public boolean createMetadataEntry();

    /*
     * Attempt to retrieve the Metadata Migration entry from the CMS, if it exists; null if it doesn't
     */
    public CmsEntry.Metadata getMetadataEntry();

    /*
     * Updates just the status field of the Metadata Migration entry in the CMS.  Returns true if the update was successful,
     */
    public boolean setMetadataMigrationStatus(CmsEntry.MetadataStatus status);

    /*
     * Updates all fields of the Metadata Migration entry in the CMS.  Returns true if the update was successful, and
     * false if something else updated it before we could
     */
    public boolean updateMetadataEntry(CmsEntry.MetadataStatus status, Integer numAttempts, String leaseExpiry);
}
