package org.opensearch.migrations.bulkload.common;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Helper class for converting RfsLuceneDocument deletions to BulkDocSection delete operations.
 */
public class DeleteDocumentHelper {
    
    /**
     * Converts a stream of RfsLuceneDocument objects to BulkDocSection delete operations.
     * 
     * @param deletions Stream of documents to be deleted
     * @param indexName The index name to use for the delete operations
     * @return Stream of BulkDocSection delete operations
     */
    public static Publisher<BulkDocSection> convertToDeleteOperations(
            Publisher<RfsLuceneDocument> deletions, 
            String indexName) {
        
        return Flux.from(deletions)
            .map(doc -> {
                // Extract document information
                String docId = doc.id;
                String type = doc.type != null ? doc.type : "_doc"; // Default to _doc if type is null
                String routing = doc.routing;
                
                // Create a delete operation
                return BulkDocSection.createDelete(docId, indexName, type, routing);
            });
    }
    
    /**
     * Converts a stream of RfsDocument objects to BulkDocSection delete operations.
     * This is useful when you have RfsDocument objects that need to be converted to deletes.
     * 
     * @param documents Stream of documents to be deleted
     * @return Stream of BulkDocSection delete operations
     */
    public static Publisher<BulkDocSection> convertRfsDocumentsToDeleteOperations(
            Publisher<RfsDocument> documents) {
        
        return Flux.from(documents)
            .map(rfsDoc -> {
                // Extract the BulkDocSection from RfsDocument
                BulkDocSection originalDoc = rfsDoc.document;
                
                // Get the document metadata from the original
                String docId = originalDoc.getDocId();
                
                // Parse the original document to get index and type information
                // This requires accessing the internal structure
                var docMap = originalDoc.toMap();
                
                String indexName = null;
                String type = "_doc";
                String routing = null;
                
                // Extract from index metadata if present
                if (docMap.containsKey("index")) {
                    @SuppressWarnings("unchecked")
                    var indexMetadata = (java.util.Map<String, Object>) docMap.get("index");
                    indexName = (String) indexMetadata.get("_index");
                    type = indexMetadata.getOrDefault("_type", "_doc").toString();
                    routing = (String) indexMetadata.get("routing");
                }
                
                if (indexName == null) {
                    throw new IllegalArgumentException("Cannot create delete operation without index name");
                }
                
                // Create a delete operation
                return BulkDocSection.createDelete(docId, indexName, type, routing);
            });
    }
}
