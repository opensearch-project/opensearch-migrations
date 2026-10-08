package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LuceneReaderVersionTest {
    private LuceneLeafReader reader;

    @BeforeEach
    void storedFields() throws IOException {
        reader = mock(LuceneLeafReader.class);
        var document = mock(LuceneDocument.class);
        var id = mock(LuceneField.class);
        var source = mock(LuceneField.class);
        when(id.name()).thenReturn("_id");
        when(id.asUid()).thenReturn("d1");
        when(source.name()).thenReturn("_source");
        when(source.utf8Value()).thenReturn("{\"ext_version\":42}".getBytes(StandardCharsets.UTF_8));
        when(document.getFields()).thenAnswer(ignored -> List.of(id, source));
        when(reader.document(0)).thenReturn(document);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 9007199254740993L, Long.MAX_VALUE})
    void extractsNumericDocValueIndependentlyOfSource(long version) throws IOException {
        when(reader.getNumericValue(0, "_version")).thenReturn(version);
        var result = read(DocumentChangeType.INDEX);
        assertEquals(version, result.version);
        assertEquals("{\"ext_version\":42}", new String(result.source, StandardCharsets.UTF_8));
    }

    @Test
    void absentDocValueLeavesVersionUnset() {
        assertNull(read(DocumentChangeType.INDEX).version);
    }

    @Test
    void versionReadFailurePropagatesInsteadOfDroppingTheDocument() throws IOException {
        when(reader.getNumericValue(0, "_version")).thenThrow(new IOException("unreadable version"));
        var failure = assertThrows(IOException.class, () -> read(DocumentChangeType.INDEX));
        assertEquals("unreadable version", failure.getMessage());
    }

    @Test
    void invalidDocValueCannotBeTruncatedOrTreatedAsAMissingVersion() throws IOException {
        when(reader.getNumericValue(0, "_version")).thenReturn(7.5);
        assertThrows(ClassCastException.class, () -> read(DocumentChangeType.INDEX));
    }

    @Test
    void deltaDeletionDoesNotUseThePreviousSnapshotsDocumentVersion() throws IOException {
        assertNull(read(DocumentChangeType.DELETE).version);
        verify(reader, never()).getNumericValue(anyInt(), anyString());
    }

    private LuceneDocumentChange read(DocumentChangeType operation) {
        return LuceneReader.getDocument(reader, 0, true, 0, () -> "test segment",
            Path.of("test-index"), operation, null, null, false);
    }
}
