package com.rfs.cms;

import java.net.HttpURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;

public class OpenSearchCmsClient implements CmsClient {
    private static final Logger logger = LogManager.getLogger(OpenSearchCmsClient.class);

    public static final String CMS_INDEX_NAME = "cms-reindex-from-snapshot";
    public static final String CMS_SNAPSHOT_DOC_ID = "snapshot_status";

    private final OpenSearchClient client;

    public OpenSearchCmsClient(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public boolean createSnapshotEntry(String snapshotName) {
        CmsEntry.Snapshot snapshot = new CmsEntry.Snapshot(snapshotName, CmsEntry.SnapshotStatus.NOT_STARTED);
        return client.createDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, snapshot.toJson());
    }

    @Override
    public CmsEntry.Snapshot getSnapshotEntry(String snapshotName) {
        RestClient.Response response = client.getDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID);

        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        return CmsEntry.Snapshot.fromJsonString(response.body);
    }

    @Override
    public boolean updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status) {
        CmsEntry.Snapshot snapshot = new CmsEntry.Snapshot(snapshotName, status);
        return client.updateDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, snapshot.toJson());
    }
    
}
