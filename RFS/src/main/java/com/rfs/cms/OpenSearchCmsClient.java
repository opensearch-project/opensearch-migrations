package com.rfs.cms;

import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;
import com.rfs.tracing.IRfsContexts;

public class OpenSearchCmsClient implements CmsClient {
    public static final String CMS_INDEX_NAME = "cms-reindex-from-snapshot";
    public static final String CMS_SNAPSHOT_DOC_ID = "snapshot_status";
    public static final String CMS_METADATA_DOC_ID = "metadata_status";

    private final OpenSearchClient client;
    private final IRfsContexts.IWorkingStateContext context;

    public OpenSearchCmsClient(OpenSearchClient client, IRfsContexts.IWorkingStateContext context) {
        this.client = client;
        this.context = context;
    }

    @Override
    public Optional<CmsEntry.Snapshot> createSnapshotEntry(String snapshotName) {
        OpenSearchCmsEntry.Snapshot newEntry = OpenSearchCmsEntry.Snapshot.getInitial(snapshotName);
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, newEntry.toJson(),
                context.createCreateSnapshotEntryDocumentContext());
        return createdEntry.map(OpenSearchCmsEntry.Snapshot::fromJson);
    }

    @Override
    public Optional<CmsEntry.Snapshot> getSnapshotEntry(String snapshotName) {
        Optional<ObjectNode> document = client.getDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID,
                context.createGetSnapshotEntryContext());
        return document.map(doc -> (ObjectNode) doc.get("_source"))
                       .map(OpenSearchCmsEntry.Snapshot::fromJson);
    }

    @Override
    public Optional<CmsEntry.Snapshot> updateSnapshotEntry(String snapshotName, CmsEntry.SnapshotStatus status) {
        OpenSearchCmsEntry.Snapshot entry = new OpenSearchCmsEntry.Snapshot(snapshotName, status);
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, entry.toJson(),
                context.createUpdateSnapshotEntryContext());
        return updatedEntry.map(OpenSearchCmsEntry.Snapshot::fromJson);
    }

    @Override
    public Optional<CmsEntry.Metadata> createMetadataEntry() {
        OpenSearchCmsEntry.Metadata entry = OpenSearchCmsEntry.Metadata.getInitial();
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, entry.toJson(),
                context.createCreateMetadataEntryDocumentContext());
        return createdEntry.map(OpenSearchCmsEntry.Metadata::fromJson);

    }

    @Override
    public Optional<CmsEntry.Metadata> getMetadataEntry() {
        Optional<ObjectNode> document = client.getDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID,
                context.createGetMetadataEntryDocument());
        return document.map(doc -> (ObjectNode) doc.get("_source"))
                       .map(OpenSearchCmsEntry.Metadata::fromJson);
    }

    @Override
    public Optional<CmsEntry.Metadata> updateMetadataEntry(CmsEntry.MetadataStatus status, String leaseExpiry, Integer numAttempts) {
        OpenSearchCmsEntry.Metadata metadata = new OpenSearchCmsEntry.Metadata(status, leaseExpiry, numAttempts);
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, metadata.toJson(),
                context.createUpdateMetadataMigrationStatusDocumentContext());
        return updatedEntry.map(OpenSearchCmsEntry.Metadata::fromJson);
    }
}
