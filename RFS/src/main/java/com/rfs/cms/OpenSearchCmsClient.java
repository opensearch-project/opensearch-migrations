package com.rfs.cms;

import java.net.HttpURLConnection;

import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;

public class OpenSearchCmsClient implements CmsClient {
    public static final String CMS_INDEX_NAME = "cms-reindex-from-snapshot";
    public static final String CMS_SNAPSHOT_DOC_ID = "snapshot_status";
    public static final String CMS_METADATA_DOC_ID = "metadata_status";

    private final OpenSearchClient client;

    public OpenSearchCmsClient(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public CmsEntry.Snapshot createSnapshotEntry(String snapshotName) {
        OpenSearchCmsEntry.Snapshot newEntry = OpenSearchCmsEntry.Snapshot.getInitial(snapshotName);
        boolean createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, newEntry.toJson());
        if (createdEntry) {
            return newEntry;
        } else {
            return null;            
        }
    }

    @Override
    public CmsEntry.Snapshot getSnapshotEntry(String snapshotName) {
        RestClient.Response response = client.getDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID);

        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        return OpenSearchCmsEntry.Snapshot.fromJsonString(response.body);
    }

    @Override
    public CmsEntry.Snapshot updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status) {
        OpenSearchCmsEntry.Snapshot entry = new OpenSearchCmsEntry.Snapshot(snapshotName, status);
        boolean updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, entry.toJson());
        if (updatedEntry) {
            return entry;
        } else {
            return null;            
        }
    }

    @Override
    public OpenSearchCmsEntry.Metadata createMetadataEntry() {
        OpenSearchCmsEntry.Metadata entry = OpenSearchCmsEntry.Metadata.getInitial();

        boolean docCreated = client.createDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, entry.toJson());

        if (docCreated) {
            return entry;
        } else {
            return null;
        }

    }

    @Override
    public CmsEntry.Metadata getMetadataEntry() {
        RestClient.Response response = client.getDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID);

        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        return OpenSearchCmsEntry.Metadata.fromJsonString(response.body);
    }

    @Override
    public CmsEntry.Metadata updateMetadataEntry(CmsEntry.MetadataStatus status, String leaseExpiry, Integer numAttempts) {
        OpenSearchCmsEntry.Metadata metadata = new OpenSearchCmsEntry.Metadata(status, leaseExpiry, numAttempts);

        boolean updatedDoc = client.updateDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, metadata.toJson());

        if (updatedDoc) {
            return metadata;
        } else {
            return null;
        }

    }
    
}
