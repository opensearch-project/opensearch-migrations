package org.opensearch.migrations.bulkload.worker;

public class OpenSearchWorkItem {
    public final String indexName;
    public final String documentId;

    public OpenSearchWorkItem(String indexName, String documentId) {
        this.indexName = indexName;
        this.documentId = documentId;
    }
}
