package com.rfs.cms;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.RfsException;

public class OpenSearchCmsClient implements CmsClient {
    private static final Logger logger = LogManager.getLogger(OpenSearchCmsClient.class);

    public static final String CMS_INDEX_NAME = "cms-reindex-from-snapshot";
    public static final String CMS_SNAPSHOT_DOC_ID = "snapshot_status";
    public static final String CMS_METADATA_DOC_ID = "metadata_status";
    public static final String CMS_INDEX_DOC_ID = "index_status";
    public static final String CMS_DOCUMENTS_DOC_ID = "documents_status";

    public static String getIndexWorkItemDocId(String name) {
        // iwi => index work item
        return "iwi_" + name;
    }

    public static String getDocumentsWorkItemDocId(String indexName, int shardId) {
        // dwi => documents work item
        return "dwi_" + indexName + "_" + shardId;
    }

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
    public Optional<CmsEntry.Snapshot> updateSnapshotEntry(CmsEntry.Snapshot newEntry, CmsEntry.Snapshot lastEntry) {
        // Pull the existing entry to ensure that it hasn't changed since we originally retrieved it
        ObjectNode currentEntryRaw = client.getDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID)
            .orElseThrow(() -> new RfsException("Failed to update snapshot entry: " + CMS_SNAPSHOT_DOC_ID + " does not exist"));

        OpenSearchCmsEntry.Snapshot currentEntry = OpenSearchCmsEntry.Snapshot.fromJson((ObjectNode) currentEntryRaw.get("_source"));
        if (!currentEntry.equals(new OpenSearchCmsEntry.Snapshot(lastEntry))) {
            logger.info("Failed to update snapshot entry: " + CMS_SNAPSHOT_DOC_ID + " has changed we first retrieved it");
            return Optional.empty();
        }

        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.Snapshot(newEntry).toJson();
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_SNAPSHOT_DOC_ID, newEntryJson, currentEntryRaw);
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
    public Optional<CmsEntry.Metadata> updateMetadataEntry(CmsEntry.Metadata newEntry, CmsEntry.Metadata lastEntry) {
        // Pull the existing entry to ensure that it hasn't changed since we originally retrieved it
        ObjectNode currentEntryRaw = client.getDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID)
            .orElseThrow(() -> new RfsException("Failed to update metadata entry: " + CMS_METADATA_DOC_ID + " does not exist"));

        OpenSearchCmsEntry.Metadata currentEntry = OpenSearchCmsEntry.Metadata.fromJson((ObjectNode) currentEntryRaw.get("_source"));
        if (!currentEntry.equals(new OpenSearchCmsEntry.Metadata(lastEntry))) {
            logger.info("Failed to update metadata entry: " + CMS_METADATA_DOC_ID + " has changed we first retrieved it");
            return Optional.empty();
        }

        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.Metadata(newEntry).toJson();
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_METADATA_DOC_ID, newEntryJson, currentEntryRaw);
        return updatedEntry.map(OpenSearchCmsEntry.Metadata::fromJson);
    }

    @Override
    public Optional<CmsEntry.Index> createIndexEntry() {
        OpenSearchCmsEntry.Index entry = OpenSearchCmsEntry.Index.getInitial();
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_INDEX_DOC_ID, entry.toJson());
        return createdEntry.map(OpenSearchCmsEntry.Index::fromJson);

    }

    @Override
    public Optional<CmsEntry.Index> getIndexEntry() {
        Optional<ObjectNode> document = client.getDocument(CMS_INDEX_NAME, CMS_INDEX_DOC_ID);
        return document.map(doc -> (ObjectNode) doc.get("_source"))
                       .map(OpenSearchCmsEntry.Index::fromJson);
    }

    @Override
    public Optional<CmsEntry.Index> updateIndexEntry(CmsEntry.Index newEntry, CmsEntry.Index lastEntry) {
        // Pull the existing entry to ensure that it hasn't changed since we originally retrieved it
        ObjectNode currentEntryRaw = client.getDocument(CMS_INDEX_NAME, CMS_INDEX_DOC_ID)
            .orElseThrow(() -> new RfsException("Failed to update index entry: " + CMS_INDEX_DOC_ID + " does not exist"));

        OpenSearchCmsEntry.Index currentEntry = OpenSearchCmsEntry.Index.fromJson((ObjectNode) currentEntryRaw.get("_source"));
        if (!currentEntry.equals(new OpenSearchCmsEntry.Index(lastEntry))) {
            logger.info("Failed to update index entry: " + CMS_INDEX_DOC_ID + " has changed we first retrieved it");
            return Optional.empty();
        }

        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.Index(newEntry).toJson();
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_INDEX_DOC_ID, newEntryJson, currentEntryRaw);
        return updatedEntry.map(OpenSearchCmsEntry.Index::fromJson);
    }

    @Override
    public Optional<CmsEntry.IndexWorkItem> createIndexWorkItem(String name, int numShards) {
        OpenSearchCmsEntry.IndexWorkItem entry = OpenSearchCmsEntry.IndexWorkItem.getInitial(name, numShards);
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, getIndexWorkItemDocId(entry.name), entry.toJson());
        return createdEntry.map(OpenSearchCmsEntry.IndexWorkItem::fromJson);
    }

    @Override
    public Optional<CmsEntry.IndexWorkItem> updateIndexWorkItem(CmsEntry.IndexWorkItem newEntry, CmsEntry.IndexWorkItem lastEntry) {
        // Pull the existing entry to ensure that it hasn't changed since we originally retrieved it
        ObjectNode currentEntryRaw = client.getDocument(CMS_INDEX_NAME, getIndexWorkItemDocId(lastEntry.name))
            .orElseThrow(() -> new RfsException("Failed to update index work item: " + getIndexWorkItemDocId(lastEntry.name) + " does not exist"));

        OpenSearchCmsEntry.IndexWorkItem currentEntry = OpenSearchCmsEntry.IndexWorkItem.fromJson((ObjectNode) currentEntryRaw.get("_source"));
        if (!currentEntry.equals(new OpenSearchCmsEntry.IndexWorkItem(lastEntry))) {
            logger.info("Failed to update index work item: " + getIndexWorkItemDocId(lastEntry.name) + " has changed we first retrieved it");
            return Optional.empty();
        }

        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.IndexWorkItem(newEntry).toJson();
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, getIndexWorkItemDocId(newEntry.name), newEntryJson, currentEntryRaw);
        return updatedEntry.map(OpenSearchCmsEntry.IndexWorkItem::fromJson);
    }

    @Override
    public CmsEntry.IndexWorkItem updateIndexWorkItemForceful(CmsEntry.IndexWorkItem newEntry) {
        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.IndexWorkItem(newEntry).toJson();
        ObjectNode updatedEntry = client.updateDocumentForceful(CMS_INDEX_NAME, getIndexWorkItemDocId(newEntry.name), newEntryJson);
        return OpenSearchCmsEntry.IndexWorkItem.fromJson(updatedEntry);
    }

    @Override
    public List<CmsEntry.IndexWorkItem> getAvailableIndexWorkItems(int maxItems) {
        // Ensure we have a relatively fresh view of the index
        client.refresh();

        // Pull the docs
        String queryBody = "{\n" +
            "  \"query\": {\n" +
            "    \"function_score\": {\n" +
            "      \"query\": {\n" +
            "        \"bool\": {\n" +
            "          \"must\": [\n" +
            "            {\n" +
            "              \"match\": {\n" +
            "                \"type\": \"INDEX_WORK_ITEM\"\n" +
            "              }\n" +
            "            },\n" +
            "            {\n" +
            "              \"match\": {\n" +
            "                \"status\": \"NOT_STARTED\"\n" +
            "              }\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      },\n" +
            "      \"random_score\": {}\n" + // Try to avoid the workers fighting for the same work items
            "    }\n" +
            "  },\n" +
            "  \"size\": " + maxItems + "\n" +
            "}";

        List<ObjectNode> hits = client.searchDocuments(CMS_INDEX_NAME, queryBody);
        List<CmsEntry.IndexWorkItem> workItems = hits.stream()
            .map(hit -> (ObjectNode) hit.get("_source"))
            .map(OpenSearchCmsEntry.IndexWorkItem::fromJson)
            .collect(Collectors.toList());

        return workItems;
    }

    @Override
    public Optional<CmsEntry.Documents> createDocumentsEntry() {
        OpenSearchCmsEntry.Documents entry = OpenSearchCmsEntry.Documents.getInitial();
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, CMS_DOCUMENTS_DOC_ID, entry.toJson());
        return createdEntry.map(OpenSearchCmsEntry.Documents::fromJson);

    }

    @Override
    public Optional<CmsEntry.Documents> getDocumentsEntry() {
        Optional<ObjectNode> document = client.getDocument(CMS_INDEX_NAME, CMS_DOCUMENTS_DOC_ID);
        return document.map(doc -> (ObjectNode) doc.get("_source"))
                       .map(OpenSearchCmsEntry.Documents::fromJson);
    }

    @Override
    public Optional<CmsEntry.Documents> updateDocumentsEntry(CmsEntry.Documents newEntry, CmsEntry.Documents lastEntry) {
        // Pull the existing entry to ensure that it hasn't changed since we originally retrieved it
        ObjectNode currentEntryRaw = client.getDocument(CMS_INDEX_NAME, CMS_DOCUMENTS_DOC_ID)
            .orElseThrow(() -> new RfsException("Failed to update documents entry: " + CMS_DOCUMENTS_DOC_ID + " does not exist"));

        OpenSearchCmsEntry.Documents currentEntry = OpenSearchCmsEntry.Documents.fromJson((ObjectNode) currentEntryRaw.get("_source"));
        if (!currentEntry.equals(new OpenSearchCmsEntry.Documents(lastEntry))) {
            logger.info("Failed to documents index entry: " + CMS_DOCUMENTS_DOC_ID + " has changed we first retrieved it");
            return Optional.empty();
        }

        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.Documents(newEntry).toJson();
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, CMS_DOCUMENTS_DOC_ID, newEntryJson, currentEntryRaw);
        return updatedEntry.map(OpenSearchCmsEntry.Documents::fromJson);
    }

    @Override
    public Optional<CmsEntry.DocumentsWorkItem> createDocumentsWorkItem(String indexName, int shardId) {
        OpenSearchCmsEntry.DocumentsWorkItem entry = OpenSearchCmsEntry.DocumentsWorkItem.getInitial(indexName, shardId);
        Optional<ObjectNode> createdEntry = client.createDocument(CMS_INDEX_NAME, getDocumentsWorkItemDocId(indexName, shardId), entry.toJson());
        return createdEntry.map(OpenSearchCmsEntry.DocumentsWorkItem::fromJson);
    }

    @Override
    public Optional<CmsEntry.DocumentsWorkItem> updateDocumentsWorkItem(CmsEntry.DocumentsWorkItem newEntry, CmsEntry.DocumentsWorkItem lastEntry) {
        String docId = getDocumentsWorkItemDocId(lastEntry.indexName, lastEntry.shardId);

        // Pull the existing entry to ensure that it hasn't changed since we originally retrieved it
        ObjectNode currentEntryRaw = client.getDocument(CMS_INDEX_NAME, docId)
            .orElseThrow(() -> new RfsException("Failed to update documents work item: " + docId + " does not exist"));

        OpenSearchCmsEntry.DocumentsWorkItem currentEntry = OpenSearchCmsEntry.DocumentsWorkItem.fromJson((ObjectNode) currentEntryRaw.get("_source"));
        if (!currentEntry.equals(new OpenSearchCmsEntry.DocumentsWorkItem(lastEntry))) {
            logger.info("Failed to update documents work item: " + docId + " has changed we first retrieved it");
            return Optional.empty();
        }

        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.DocumentsWorkItem(newEntry).toJson();
        Optional<ObjectNode> updatedEntry = client.updateDocument(CMS_INDEX_NAME, docId, newEntryJson, currentEntryRaw);
        return updatedEntry.map(OpenSearchCmsEntry.DocumentsWorkItem::fromJson);
    }

    @Override
    public CmsEntry.DocumentsWorkItem updateDocumentsWorkItemForceful(CmsEntry.DocumentsWorkItem newEntry) {
        // Now attempt the update
        ObjectNode newEntryJson = new OpenSearchCmsEntry.DocumentsWorkItem(newEntry).toJson();
        ObjectNode updatedEntry = client.updateDocumentForceful(CMS_INDEX_NAME, getDocumentsWorkItemDocId(newEntry.indexName, newEntry.shardId), newEntryJson);
        return OpenSearchCmsEntry.DocumentsWorkItem.fromJson(updatedEntry);
    }

    @Override
    public Optional<CmsEntry.DocumentsWorkItem> getAvailableDocumentsWorkItem() {
        // Ensure we have a relatively fresh view of the index
        client.refresh();

        // Pull the docs
        String queryBody = "{\n" +
            "  \"query\": {\n" +
            "    \"function_score\": {\n" +
            "      \"query\": {\n" +
            "        \"bool\": {\n" +
            "          \"must\": [\n" +
            "            {\n" +
            "              \"match\": {\n" +
            "                \"type\": \"DOCUMENTS_WORK_ITEM\"\n" +
            "              }\n" +
            "            },\n" +
            "            {\n" +
            "              \"match\": {\n" +
            "                \"status\": \"NOT_STARTED\"\n" +
            "              }\n" +
            "            }\n" +
            "          ]\n" +
            "        }\n" +
            "      },\n" +
            "      \"random_score\": {}\n" + // Try to avoid the workers fighting for the same work items
            "    }\n" +
            "  },\n" +
            "  \"size\": 1\n" + // At most one result
            "}";

        List<ObjectNode> hits = client.searchDocuments(CMS_INDEX_NAME, queryBody);
        List<CmsEntry.DocumentsWorkItem> workItems = hits.stream()
            .map(hit -> (ObjectNode) hit.get("_source"))
            .map(OpenSearchCmsEntry.DocumentsWorkItem::fromJson)
            .collect(Collectors.toList());

        return workItems.isEmpty() ? Optional.empty() : Optional.of(workItems.get(0));
    }
}
