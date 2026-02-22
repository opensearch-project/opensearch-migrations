package org.opensearch.migrations.bulkload.pipeline;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneAdapter;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the adapter that bridges existing Lucene types to the clean pipeline IR.
 */
class LuceneAdapterTest {

    @Test
    void convertsIndexOperationWithAllFields() {
        var luceneDoc = new LuceneDocumentChange(
            42,                          // luceneDocNumber — dropped in clean IR
            "doc-1",
            "_doc",
            "{\"field\":\"value\"}".getBytes(),
            "custom-routing",
            DocumentChangeType.INDEX
        );

        DocumentChange clean = LuceneAdapter.fromLucene(luceneDoc);

        assertEquals("doc-1", clean.id());
        assertEquals("_doc", clean.type());
        assertArrayEquals("{\"field\":\"value\"}".getBytes(), clean.source());
        assertEquals("custom-routing", clean.routing());
        assertEquals(DocumentChange.ChangeType.INDEX, clean.operation());
    }

    @Test
    void convertsDeleteOperationWithNullFields() {
        var luceneDoc = new LuceneDocumentChange(
            99, "doc-2", null, null, null, DocumentChangeType.DELETE
        );

        DocumentChange clean = LuceneAdapter.fromLucene(luceneDoc);

        assertEquals("doc-2", clean.id());
        assertNull(clean.type());
        assertNull(clean.source());
        assertNull(clean.routing());
        assertEquals(DocumentChange.ChangeType.DELETE, clean.operation());
    }

    @Test
    void dropsLuceneDocNumber() {
        var luceneDoc = new LuceneDocumentChange(
            12345, "doc-3", null, "{}".getBytes(), null, DocumentChangeType.INDEX
        );

        DocumentChange clean = LuceneAdapter.fromLucene(luceneDoc);

        // The clean IR has no luceneDocNumber field — this is intentional.
        // We verify the conversion doesn't fail and all other fields are preserved.
        assertEquals("doc-3", clean.id());
        assertArrayEquals("{}".getBytes(), clean.source());
    }

    @Test
    void preservesLargeDocumentSource() {
        byte[] largeSource = new byte[10_000_000]; // 10MB
        java.util.Arrays.fill(largeSource, (byte) 'x');

        var luceneDoc = new LuceneDocumentChange(
            0, "big-doc", "_doc", largeSource, null, DocumentChangeType.INDEX
        );

        DocumentChange clean = LuceneAdapter.fromLucene(luceneDoc);

        assertSame(largeSource, clean.source(), "Should reference same byte array, not copy");
    }
}
