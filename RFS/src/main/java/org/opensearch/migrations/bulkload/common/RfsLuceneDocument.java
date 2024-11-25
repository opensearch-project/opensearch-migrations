package org.opensearch.migrations.bulkload.common;

import lombok.RequiredArgsConstructor;

/** 
 * This class represents a document at the Lucene level within RFS.  It tracks where the document was within the Lucene
 * index, as well as the document's embedded Elasticsearch/OpenSearch properties
 */
@RequiredArgsConstructor
public class RfsLuceneDocument {
    // The Lucene segment identifier of the document
    public final int luceneSegId;

    // The Lucene document identifier of the document
    public final int luceneDocId;

    // The Elasticsearch/OpenSearch document identifier (_id) of the document
    public final String id;

    // The Elasticsearch/OpenSearch _type of the document
    public final String type;

    // The Elasticsearch/OpenSearch _source of the document
    public final String source;
}
