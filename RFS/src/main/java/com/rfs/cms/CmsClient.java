package com.rfs.cms;

import java.util.Optional;

/*
 * Client to connect to and work with the Coordinating Metadata Store.  The CMS could be implemented by any reasonable
 * data store option (Postgres, AWS DynamoDB, Elasticsearch/Opensearch, etc).
 */
public interface CmsClient {
    /*
     * Creates a new entry in the CMS for the Snapshot's progress.  Returns an Optional; if the document was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<CmsEntry.Snapshot> createSnapshotEntry(String snapshotName);

    /*
     * Attempt to retrieve the Snapshot entry from the CMS.  Returns an Optional; if the document exists, it will be the
     * retrieved entry and empty otherwise.
     */
    public Optional<CmsEntry.Snapshot> getSnapshotEntry(String snapshotName);

    /*
     * Updates the Snapshot entry in the CMS.  Returns an Optional; if the document was updated, it will be
     * the updated entry and empty otherwise.
     */
    public Optional<CmsEntry.Snapshot> updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status);

    /*
     * Creates a new entry in the CMS for the Metadata Migration's progress.  Returns an Optional; if the document was
     * created, it will be the created entry and empty otherwise.
     */
    public Optional<CmsEntry.Metadata> createMetadataEntry();

    /*
     * Attempt to retrieve the Metadata Migration entry from the CMS, if it exists.  Returns an Optional; if the document
     * exists, it will be the retrieved entry and empty otherwise.
     */
    public Optional<CmsEntry.Metadata> getMetadataEntry();

    /*
     * Updates the Metadata Migration entry in the CMS.  Returns an Optional; if the document was updated,
     * it will be the updated entry and empty otherwise.
     */
    public Optional<CmsEntry.Metadata> updateMetadataEntry(CmsEntry.MetadataStatus status, String leaseExpiry, Integer numAttempts);
}
