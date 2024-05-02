package com.rfs.cms;

/*
 * Client to connect to and work with the Coordinating Metadata Store.  The CMS could be implemented by any reasonable
 * data store option (Postgres, AWS DynamoDB, Elasticsearch/Opensearch, etc).
 */
public interface CmsClient {
    public boolean createSnapshotEntry(String snapshotName);
    public CmsEntry.Snapshot getSnapshotEntry(String snapshotName);
    public boolean updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status);
}
