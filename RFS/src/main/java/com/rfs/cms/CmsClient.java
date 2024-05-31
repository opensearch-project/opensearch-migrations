package com.rfs.cms;

import java.util.List;
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
    public Optional<CmsEntry.Snapshot> updateSnapshotEntry(CmsEntry.Snapshot newEntry, CmsEntry.Snapshot lastEntry);

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
    public Optional<CmsEntry.Metadata> updateMetadataEntry(CmsEntry.Metadata newEntry, CmsEntry.Metadata lastEntry);

    /*
     * Creates a new entry in the CMS for the Index Migration's progress.  Returns an Optional; if the document was
     * created, it will be the created entry and empty otherwise.
     */
    public Optional<CmsEntry.Index> createIndexEntry();

    /*
     * Attempt to retrieve the Index Migration entry from the CMS, if it exists.  Returns an Optional; if the document
     * exists, it will be the retrieved entry and empty otherwise.
     */
    public Optional<CmsEntry.Index> getIndexEntry();

    /*
     * Updates the Index Migration entry in the CMS.  Returns an Optional; if the document was updated,
     * it will be the updated entry and empty otherwise.
     */
    public Optional<CmsEntry.Index> updateIndexEntry(CmsEntry.Index newEntry, CmsEntry.Index lastEntry);

    /*
     * Creates a new entry in the CMS for an Index Work Item.  Returns an Optional; if the document was
     * created, it will be the created entry and empty otherwise.
     */
    public Optional<CmsEntry.IndexWorkItem> createIndexWorkItem(String name, int numShards);

    /*
     * Updates the Index Work Item in the CMS.  Returns an Optional; if the document was updated,
     * it will be the updated entry and empty otherwise.
     */
    public Optional<CmsEntry.IndexWorkItem> updateIndexWorkItem(CmsEntry.IndexWorkItem newEntry, CmsEntry.IndexWorkItem lastEntry);

    /*
     * Forcefully updates the Index Work Item in the CMS.  This method should be used when you don't care about collisions
     * and just want to overwrite the existing entry no matter what.  Returns the updated entry.
     */
    public CmsEntry.IndexWorkItem updateIndexWorkItemForceful(CmsEntry.IndexWorkItem newEntry);

    /* 
     * Retrieves a set of Index Work Items from the CMS that appear ready to be worked on, up to the specified limit.
     */
    public List<CmsEntry.IndexWorkItem> getAvailableIndexWorkItems(int maxItems);
}
