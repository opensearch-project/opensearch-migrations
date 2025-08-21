package org.opensearch.migrations.bulkload.common;

import org.opensearch.migrations.bulkload.common.bulk.DeleteOp;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.enums.RfsDocumentOperation;
import org.opensearch.migrations.bulkload.common.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.common.operations.IndexOperationMeta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RfsDocumentOperationTest {

    @Test
    void testRfsLuceneDocumentWithIndexOperation() {
        // Create a document with INDEX operation
        RfsLuceneDocument doc = new RfsLuceneDocument(
            1,
            "doc-id",
            "type",
            "{\"field\":\"value\"}",
            "routing",
            RfsDocumentOperation.INDEX
        );
        
        assertEquals(RfsDocumentOperation.INDEX, doc.operation);
        assertEquals("doc-id", doc.id);
        assertEquals("{\"field\":\"value\"}", doc.source);
    }

    @Test
    void testRfsLuceneDocumentWithDeleteOperation() {
        // Create a document with DELETE operation
        RfsLuceneDocument doc = new RfsLuceneDocument(
            2,
            "delete-id",
            "type",
            "{\"original\":\"content\"}",  // Preserve original source
            "routing",
            RfsDocumentOperation.DELETE
        );
        
        assertEquals(RfsDocumentOperation.DELETE, doc.operation);
        assertEquals("delete-id", doc.id);
        assertEquals("{\"original\":\"content\"}", doc.source);
    }

    @Test
    void testRfsDocumentFromLuceneDocumentWithIndexOperation() {
        // Create a Lucene document with INDEX operation
        RfsLuceneDocument luceneDoc = new RfsLuceneDocument(
            1,
            "doc-id",
            null,
            "{\"field\":\"value\"}",
            null,
            RfsDocumentOperation.INDEX
        );
        
        // Convert to RfsDocument
        RfsDocument rfsDoc = RfsDocument.fromLuceneDocument(luceneDoc, "test-index");
        
        assertNotNull(rfsDoc);
        assertEquals(1, rfsDoc.progressCheckpointNum);
        assertTrue(rfsDoc.document instanceof IndexOp);
        
        IndexOp indexOp = (IndexOp) rfsDoc.document;
        assertTrue(indexOp.isIncludeDocument());
        
        IndexOperationMeta meta = (IndexOperationMeta) indexOp.getOperation();
        assertEquals("doc-id", meta.getId());
        assertEquals("test-index", meta.getIndex());
    }

    @Test
    void testRfsDocumentFromLuceneDocumentWithDeleteOperation() {
        // Create a Lucene document with DELETE operation
        RfsLuceneDocument luceneDoc = new RfsLuceneDocument(
            2,
            "delete-id",
            "type",
            "{\"original\":\"content\"}",  // Has source content
            "routing",
            RfsDocumentOperation.DELETE
        );
        
        // Convert to RfsDocument
        RfsDocument rfsDoc = RfsDocument.fromLuceneDocument(luceneDoc, "test-index");
        
        assertNotNull(rfsDoc);
        assertEquals(2, rfsDoc.progressCheckpointNum);
        assertTrue(rfsDoc.document instanceof DeleteOp);
        
        DeleteOp deleteOp = (DeleteOp) rfsDoc.document;
        assertFalse(deleteOp.isIncludeDocument());  // Delete operations don't include document in bulk
        
        DeleteOperationMeta meta = (DeleteOperationMeta) deleteOp.getOperation();
        assertEquals("delete-id", meta.getId());
        assertEquals("test-index", meta.getIndex());
        assertEquals("type", meta.getType());
        assertEquals("routing", meta.getRouting());
        
        // But the document should be preserved for transformations
        assertNotNull(deleteOp.getDocument());
        assertEquals("content", deleteOp.getDocument().get("original"));
    }

    @Test
    void testRfsDocumentFromLuceneDocumentWithDeleteOperationNoSource() {
        // Create a Lucene document with DELETE operation but no source
        RfsLuceneDocument luceneDoc = new RfsLuceneDocument(
            3,
            "delete-id-no-source",
            "type",
            null,  // No source content
            null,
            RfsDocumentOperation.DELETE
        );
        
        // Convert to RfsDocument
        RfsDocument rfsDoc = RfsDocument.fromLuceneDocument(luceneDoc, "test-index");
        
        assertNotNull(rfsDoc);
        assertEquals(3, rfsDoc.progressCheckpointNum);
        assertTrue(rfsDoc.document instanceof DeleteOp);
        
        DeleteOp deleteOp = (DeleteOp) rfsDoc.document;
        assertFalse(deleteOp.isIncludeDocument());
        
        DeleteOperationMeta meta = (DeleteOperationMeta) deleteOp.getOperation();
        assertEquals("delete-id-no-source", meta.getId());
        assertNull(deleteOp.getDocument());  // No document when source is null
    }
}
