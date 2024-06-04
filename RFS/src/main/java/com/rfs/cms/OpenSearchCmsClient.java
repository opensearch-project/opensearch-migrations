package com.rfs.cms;

import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.OpenSearchClient;

public class OpenSearchCmsClient implements CmsClient {
    public static final String CMS_INDEX_NAME = "cms-reindex-from-snapshot";
    public static final String CMS_SNAPSHOT_DOC_ID = "snapshot_status";
    public static final String CMS_METADATA_DOC_ID = "metadata_status";

    private final OpenSearchClient client;

    public OpenSearchCmsClient(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public Optional<CmsEntry.Snapshot> createSnapshotEntry(String snapshotName) {
        OpenSearchCmsEntry.Snapshot newEntry = OpenSearchCmsEntry.Snapshot.getInitial(snapshotName);
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, newEntry.toJson());
        return createdEntry.map(OpenSearchCmsEntry.Snapshot::fromJson);
    }

    @Override
    public Optional<CmsEntry.Snapshot> getSnapshotEntry(String snapshotName) {
        Optional<ObjectNode> document = client.getDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID);
        return document.map(doc -> (ObjectNode) doc.get("_source"))
                       .map(OpenSearchCmsEntry.Snapshot::fromJson);
    }

    @Override
    public Optional<CmsEntry.Snapshot> updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status) {
        OpenSearchCmsEntry.Snapshot entry = new OpenSearchCmsEntry.Snapshot(snapshotName, status);
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, entry.toJson());
        return updatedEntry.map(OpenSearchCmsEntry.Snapshot::fromJson);
    }

    @Override
    public Optional<CmsEntry.Metadata> createMetadataEntry() {
        OpenSearchCmsEntry.Metadata entry = OpenSearchCmsEntry.Metadata.getInitial();
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, entry.toJson());
        return createdEntry.map(OpenSearchCmsEntry.Metadata::fromJson);

    }

    @Override
    public Optional<CmsEntry.Metadata> getMetadataEntry() {
        Optional<ObjectNode> document = client.getDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID);
        return document.map(doc -> (ObjectNode) doc.get("_source"))
                       .map(OpenSearchCmsEntry.Metadata::fromJson);
    }

    @Override
    public Optional<CmsEntry.Metadata> updateMetadataEntry(CmsEntry.MetadataStatus status, String leaseExpiry, Integer numAttempts) {
        OpenSearchCmsEntry.Metadata metadata = new OpenSearchCmsEntry.Metadata(status, leaseExpiry, numAttempts);
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, metadata.toJson());
        return updatedEntry.map(OpenSearchCmsEntry.Metadata::fromJson);
    }    
}
