package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.smile.databind.SmileMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The reader hands downstream stages JSON regardless of the format the source was indexed in. */
class LuceneReaderSourceFormatTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> DOC = Map.of("title", "héllo", "n", 42, "tags", List.of("a", "b"));

    private LuceneLeafReader reader(String sourceField, byte[] source) throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var document = mock(LuceneDocument.class);
        var id = mock(LuceneField.class);
        var field = mock(LuceneField.class);
        when(id.name()).thenReturn("_id");
        when(id.asUid()).thenReturn("d1");
        when(field.name()).thenReturn(sourceField);
        when(field.utf8Value()).thenReturn(source);
        when(document.getFields()).thenAnswer(ignored -> List.of(id, field));
        when(reader.document(0)).thenReturn(document);
        return reader;
    }

    private static LuceneDocumentChange read(LuceneLeafReader reader, DocumentChangeType operation,
                                             FieldMappingContext mappingContext, boolean useRecoverySource) {
        return LuceneReader.getDocument(reader, 0, true, 0, () -> "test segment",
            Path.of("test-index"), operation, mappingContext, null, useRecoverySource);
    }

    @Test
    void jsonSourceIsPassedThroughUntouched() throws IOException {
        byte[] json = JSON.writeValueAsBytes(DOC);
        var change = read(reader("_source", json), DocumentChangeType.INDEX, null, false);
        assertSame(json, change.getSource());
        assertNull(change.getSourceDecodeError());
    }

    @Test
    void smileSourceIsConvertedToJson() throws IOException {
        var change = read(reader("_source", new SmileMapper().writeValueAsBytes(DOC)),
            DocumentChangeType.INDEX, null, false);
        assertNull(change.getSourceDecodeError());
        assertEquals(JSON.valueToTree(DOC), JSON.readTree(change.getSource()));
    }

    @Test
    void cborRecoverySourceIsConvertedToJson() throws IOException {
        var change = read(reader("_recovery_source", new CBORMapper().writeValueAsBytes(DOC)),
            DocumentChangeType.INDEX, null, true);
        assertNull(change.getSourceDecodeError());
        assertEquals(JSON.valueToTree(DOC), JSON.readTree(change.getSource()));
    }

    @Test
    void smileSourceIsConvertedBeforeMergingWithDocValues() throws IOException {
        // The merge works on JSON text; feeding it SMILE used to corrupt the bytes through a String round-trip.
        var change = read(reader("_source", new SmileMapper().writeValueAsBytes(DOC)),
            DocumentChangeType.INDEX, new FieldMappingContext(null), false);
        assertNull(change.getSourceDecodeError());
        assertEquals(JSON.valueToTree(DOC), JSON.readTree(change.getSource()));
    }

    @Test
    void undecodableSourceIsKeptAndFlaggedInsteadOfSkipped() throws IOException {
        byte[] garbage = {0x01, 0x02, 0x03, (byte) 0xFF};
        var change = read(reader("_source", garbage), DocumentChangeType.INDEX, new FieldMappingContext(null), false);
        assertNotNull(change, "the document must reach the sink so it can be reported as failed");
        assertEquals("d1", change.getId());
        assertArrayEquals(garbage, change.getSource());
        assertTrue(change.getSourceDecodeError().contains("01 02 03 ff"), change.getSourceDecodeError());
    }
}
