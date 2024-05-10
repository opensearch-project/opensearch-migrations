package com.rfs.cms;

import java.net.HttpURLConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;

public class OpenSearchCmsClient implements CmsClient {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final String CMS_INDEX_NAME = "cms-reindex-from-snapshot";
    public static final String CMS_SNAPSHOT_DOC_ID = "snapshot_status";
    public static final String CMS_METADATA_DOC_ID = "metadata_status";

    private final OpenSearchClient client;

    public OpenSearchCmsClient(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public boolean createSnapshotEntry(String snapshotName) {
        ObjectNode initial = OpenSearchCmsEntry.Snapshot.getInitial(snapshotName);
        return client.createDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, initial);
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
    public boolean updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status) {
        OpenSearchCmsEntry.Snapshot snapshot = new OpenSearchCmsEntry.Snapshot(snapshotName, status);
        return client.updateDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, snapshot.toJson());
    }

    @Override
    public boolean createMetadataEntry() {
        ObjectNode metadataDoc = OpenSearchCmsEntry.Metadata.getInitial();
        return client.createDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, metadataDoc);
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
    public boolean setMetadataMigrationStatus(CmsEntry.MetadataStatus status) {
        ObjectNode statusUpdate = objectMapper.createObjectNode();
        statusUpdate.put(OpenSearchCmsEntry.Metadata.FIELD_STATUS, status.toString());
        return client.updateDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, statusUpdate);
    }

    @Override
    public boolean updateMetadataEntry(CmsEntry.MetadataStatus status, String leaseExpiry, Integer numAttempts) {
        OpenSearchCmsEntry.Metadata metadata = new OpenSearchCmsEntry.Metadata(status, leaseExpiry, numAttempts);
        return client.updateDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, metadata.toJson());
    }
    
}
