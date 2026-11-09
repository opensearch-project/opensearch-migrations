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
    public final byte[] source;

    // The Elasticsearch/OpenSearch custom shard routing of the document
    public final String routing;

    // The operation type for reindexing this document
    public final DocumentChangeType operation;

    // The snapshot's _version doc value, when available. A delta deletion does not
    // have a version: the previous snapshot only contains the pre-deletion version.
    public final Long version;

    // Why the stored _source could not be converted to JSON, or null when {@link #source} is JSON.
    // When set, {@link #source} holds the bytes exactly as stored, for failure reporting.
    public final String sourceDecodeError;

    public LuceneDocumentChange(int luceneDocNumber, String id, String type, byte[] source,
                                String routing, DocumentChangeType operation) {
        this(luceneDocNumber, id, type, source, routing, operation, null);
    }

    public LuceneDocumentChange(int luceneDocNumber, String id, String type, byte[] source,
                                String routing, DocumentChangeType operation, Long version) {
        this(luceneDocNumber, id, type, source, routing, operation, version, null);
    }
}
