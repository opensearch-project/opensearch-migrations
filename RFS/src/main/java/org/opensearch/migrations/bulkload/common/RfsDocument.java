package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.bulk.*;
import org.opensearch.migrations.bulkload.common.enums.RfsDocumentOperation;
import org.opensearch.migrations.bulkload.common.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.common.operations.IndexOperationMeta;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
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
    // Originally set to the lucene index doc number, this number helps keeps track of progress over a work item
    public final int progressCheckpointNum;

    // The Elasticsearch/OpenSearch document to be reindexed
    public final BulkOperationSpec document;

    public static RfsDocument fromLuceneDocument(RfsLuceneDocument doc, String indexName) {
        // Check if this is a delete operation
        Map<String, Object> document = null;
        if (doc.source != null) {
            try {
                document = OBJECT_MAPPER.readValue(doc.source, new TypeReference<>() {});
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to parse source doc: " + e.getMessage(), e);
            }
        }

        if (doc.operation == RfsDocumentOperation.DELETE) {
            // Create a delete operation
            DeleteOperationMeta meta = DeleteOperationMeta.builder()
                .id(doc.id)
                .index(indexName)
                .type(doc.type)
                .routing(doc.routing)
                .build();
            DeleteOp deleteOp = DeleteOp.builder()
                .operation(meta)
                .document(document)
                .build();
            return new RfsDocument(doc.luceneDocNumber, deleteOp);
        } else {
            // Create a normal index operation
            IndexOperationMeta meta = IndexOperationMeta.builder()
                .id(doc.id)
                .index(indexName)
                .type(doc.type)
                .routing(doc.routing)
                .build();
            IndexOp indexOp = IndexOp.builder()
                .operation(meta)
                .document(document)
                .build();
            return new RfsDocument(doc.luceneDocNumber, indexOp);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<RfsDocument> transform(IJsonTransformer transformer, List<RfsDocument> docs) {
        var listOfDocMaps = docs.stream().map(doc -> OBJECT_MAPPER.convertValue(doc.document, Map.class))
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
