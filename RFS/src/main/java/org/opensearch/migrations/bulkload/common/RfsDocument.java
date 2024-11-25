package org.opensearch.migrations.bulkload.common;

import java.util.Map;
import java.util.function.Function;

import lombok.AllArgsConstructor;

/** 
 * This class represents a document within RFS during the Reindexing process.  It tracks:
 * * The original Lucene context of the document (Lucene segment and document identifiers)
 * * The original Elasticsearch/OpenSearch context of the document (Index and Shard)
 * * The final shape of the document as needed for reindexing
 */
@AllArgsConstructor
public class RfsDocument {
    // The Lucene segment identifier of the document
    public final int luceneSegId;

    // The Lucene document identifier of the document
    public final int luceneDocId;

    // The original ElasticSearch/OpenSearch Index the document was in
    public final String indexName;

    // The original ElasticSearch/OpenSearch shard the document was in
    public final int shardNumber;

    // The Elasticsearch/OpenSearch document to be reindexed
    public final BulkDocSection document;

    public static RfsDocument fromLuceneDocument(RfsLuceneDocument doc, String indexName, int shardNumber) {
        return new RfsDocument(
            doc.luceneSegId,
            doc.luceneDocId,
            indexName,
            shardNumber,
            new BulkDocSection(
                doc.id,
                indexName,
                doc.type,
                doc.source,
                doc.routing
            )
        );
    }

    public static RfsDocument transform(Function<Map<String, Object>, Map<String, Object>> transformer, RfsDocument doc) {
        return new RfsDocument(
            doc.luceneSegId,
            doc.luceneDocId,
            doc.indexName,
            doc.shardNumber,
            BulkDocSection.fromMap(transformer.apply(doc.document.toMap()))
        );
    }
}
