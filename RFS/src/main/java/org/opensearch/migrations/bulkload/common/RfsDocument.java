package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.bulk.*;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneAdapter;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;

/** 
 * This class represents a document within RFS during the Reindexing process.  It tracks:
 * * The original Lucene context of the document (Lucene segment and document identifiers)
 * * The original Elasticsearch/OpenSearch context of the document (Index and Shard)
 * * The final shape of the document as needed for reindexing
 */
@AllArgsConstructor
public class RfsDocument {
    protected static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final LuceneAdapter LUCENE_ADAPTER = new LuceneAdapter(true);
    // Originally set to the lucene index doc number, this number helps keeps track of progress over a work item
    public final int progressCheckpointNum;

    // The Elasticsearch/OpenSearch document to be reindexed
    public final BulkOperationSpec document;

    public static RfsDocument fromLuceneDocument(LuceneDocumentChange doc, String indexName) {
        var document = LUCENE_ADAPTER.fromLucene(doc);
        return new RfsDocument(doc.luceneDocNumber, BulkOperationConverter.fromDocument(document, indexName));
    }

    @SuppressWarnings("unchecked")
    public static List<RfsDocument> transform(IJsonTransformer transformer, List<RfsDocument> docs) {
        var listOfDocMaps = docs.stream().map(doc -> doc.document.toTransformerMap(OBJECT_MAPPER))
                .toList();

        // Use the first progressCheckpointNum in the batch to associate with all returned objects
        var progressCheckpointNum = docs.stream().findFirst().map(doc -> doc.progressCheckpointNum).orElseThrow(
                () -> new IllegalArgumentException("Expected non-empty list of docs, but was empty.")
        );

        var transformedObject = transformer.transformJson(listOfDocMaps);
        if (transformedObject instanceof List) {
            var transformedList = (List<Map<String, Object>>) transformedObject;
            return transformedList.stream()
                .map(item -> new RfsDocument(
                    progressCheckpointNum,
                    OBJECT_MAPPER.convertValue(item, BulkOperationSpec.class)
                ))
                .collect(Collectors.toList());
        } else {
            throw new IllegalArgumentException(
                "Unsupported transformed document type: " + transformedObject.getClass().getName()
            );
        }
    }
}
