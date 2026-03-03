package org.opensearch.migrations.bulkload.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * This class represents a document change at the Lucene level within RFS. It tracks where the document was within the
 * Lucene index, as well as the document's embedded Elasticsearch/OpenSearch properties and the type of change
 * (INDEX or DELETE).
 */
@RequiredArgsConstructor
@Getter
public class LuceneDocumentChange {
    // The Lucene document number of the document
    public final int luceneDocNumber;

    // The Elasticsearch/OpenSearch document identifier (_id) of the document
    public final String id;

    // The Elasticsearch/OpenSearch _type of the document
    public final String type;

    // The Elasticsearch/OpenSearch _source of the document
    public final String source;

    // The Elasticsearch/OpenSearch custom shard routing of the document
    public final String routing;

    // The operation type for reindexing this document
    public final DocumentChangeType operation;
}
