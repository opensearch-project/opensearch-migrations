package org.opensearch.migrations.bulkload.common;

import java.util.Map;
import java.util.function.UnaryOperator;

import lombok.AllArgsConstructor;

/** 
 * This class represents a document within RFS during the Reindexing process.  It tracks:
 * * The original Lucene context of the document (Lucene segment and document identifiers)
 * * The original Elasticsearch/OpenSearch context of the document (Index and Shard)
 * * The final shape of the document as needed for reindexing
 */
@AllArgsConstructor
public class RfsDocument {
    // The Lucene index doc number of the document (global over shard / lucene-index)
    public final int luceneDocNumber;

    // The Elasticsearch/OpenSearch document to be reindexed
    public final BulkDocSection document;

    public static RfsDocument fromLuceneDocument(RfsLuceneDocument doc, String indexName) {
        return new RfsDocument(
            doc.luceneDocNumber,
            new BulkDocSection(
                doc.id,
                indexName,
                doc.type,
                doc.source,
                doc.routing
            )
        );
    }

    public static RfsDocument transform(UnaryOperator<Map<String, Object>> transformer, RfsDocument doc) {
        return new RfsDocument(
            doc.luceneDocNumber,
            BulkDocSection.fromMap(transformer.apply(doc.document.toMap()))
        );
    }
}
