package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The reader hands downstream stages JSON regardless of the format the source was indexed in. */
class LuceneReaderSourceFormatTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> DOC = Map.of("title", "héllo", "n", 42, "tags", List.of("a", "b"));

    private static LuceneLeafReader reader(String sourceField, byte[] source) throws IOException {
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
        assertSame(json, read(reader("_source", json), DocumentChangeType.INDEX, null, false).getSource());
    }

    @Test
    void smileSourceIsConvertedToJson() throws IOException {
        byte[] smile = new ObjectMapper(new SmileFactory()).writeValueAsBytes(DOC);
        var change = read(reader("_source", smile), DocumentChangeType.INDEX, null, false);
        assertEquals(JSON.valueToTree(DOC), JSON.readTree(change.getSource()));
    }

    @Test
    void cborRecoverySourceIsConvertedToJson() throws IOException {
        byte[] cbor = new ObjectMapper(new CBORFactory()).writeValueAsBytes(DOC);
        var change = read(reader("_recovery_source", cbor), DocumentChangeType.INDEX, null, true);
        assertEquals(JSON.valueToTree(DOC), JSON.readTree(change.getSource()));
    }

    @Test
    void smileSourceIsConvertedBeforeMergingWithDocValues() throws IOException {
        // The merge works on JSON text; SMILE fed to it would be corrupted by a String round-trip.
        byte[] smile = new ObjectMapper(new SmileFactory()).writeValueAsBytes(DOC);
        var change = read(reader("_source", smile), DocumentChangeType.INDEX, new FieldMappingContext(null), false);
        assertEquals(JSON.valueToTree(DOC), JSON.readTree(change.getSource()));
    }

    @Test
    void unconvertibleSourceKeepsItsStoredBytesAndSkipsTheMerge() throws IOException {
        byte[] garbage = {0x01, 0x02, 0x03, (byte) 0xFF};
        var change = read(reader("_source", garbage), DocumentChangeType.INDEX, new FieldMappingContext(null), false);
        assertNotNull(change, "the document must reach the sink so it can be reported as failed");
        assertEquals("d1", change.getId());
        assertArrayEquals(garbage, change.getSource());
    }
}
