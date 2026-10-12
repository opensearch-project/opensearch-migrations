package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.version_10.LeafReader10;
import org.opensearch.migrations.bulkload.lucene.version_5.LeafReader5;
import org.opensearch.migrations.bulkload.lucene.version_6.LeafReader6;
import org.opensearch.migrations.bulkload.lucene.version_7.LeafReader7;
import org.opensearch.migrations.bulkload.lucene.version_9.LeafReader9;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LuceneNumericValueReaderTest {
    @Test
    void legacyFiveCapturesNumericValuesOnce() throws IOException {
        var wrapped = mock(shadow.lucene5.org.apache.lucene.index.LeafReader.class);
        var values = mock(shadow.lucene5.org.apache.lucene.index.NumericDocValues.class);
        when(wrapped.getNumericDocValues("_version")).thenReturn(values);
        when(values.get(0)).thenReturn(1L);
        when(values.get(1)).thenReturn(Long.MAX_VALUE);

        var cursor = new LeafReader5(wrapped).openNumericValueReader("_version");

        assertEquals(1L, cursor.get(0));
        assertEquals(Long.MAX_VALUE, cursor.get(1));
        verify(wrapped, times(1)).getNumericDocValues("_version");
    }

    @Test
    void legacySixCapturesNumericValuesOnce() throws IOException {
        var wrapped = mock(shadow.lucene6.org.apache.lucene.index.LeafReader.class);
        var values = mock(shadow.lucene6.org.apache.lucene.index.NumericDocValues.class);
        when(wrapped.getNumericDocValues("_version")).thenReturn(values);
        when(values.get(0)).thenReturn(1L);
        when(values.get(1)).thenReturn(Long.MAX_VALUE);

        var cursor = new LeafReader6(wrapped).openNumericValueReader("_version");

        assertEquals(1L, cursor.get(0));
        assertEquals(Long.MAX_VALUE, cursor.get(1));
        verify(wrapped, times(1)).getNumericDocValues("_version");
    }

    @Test
    void sevenPreservesMissingValuesAndLongPrecision() throws IOException {
        var wrapped = mock(shadow.lucene7.org.apache.lucene.index.LeafReader.class);
        var values = mock(shadow.lucene7.org.apache.lucene.index.NumericDocValues.class);
        when(wrapped.getNumericDocValues("_version")).thenReturn(values);
        when(values.advanceExact(1)).thenReturn(true);
        when(values.longValue()).thenReturn(Long.MAX_VALUE);

        var cursor = new LeafReader7(wrapped).openNumericValueReader("_version");

        assertNull(cursor.get(0));
        assertEquals(Long.MAX_VALUE, cursor.get(1));
        verify(wrapped, times(1)).getNumericDocValues("_version");
    }

    @Test
    void nineUsesIndependentCursorsOutsideReconstructionCache() throws IOException {
        var wrapped = mock(shadow.lucene9.org.apache.lucene.index.LeafReader.class);
        var reconstructed = mock(shadow.lucene9.org.apache.lucene.index.NumericDocValues.class);
        var first = mock(shadow.lucene9.org.apache.lucene.index.NumericDocValues.class);
        var second = mock(shadow.lucene9.org.apache.lucene.index.NumericDocValues.class);
        when(wrapped.getNumericDocValues("_version")).thenReturn(reconstructed, first, second);
        when(first.advanceExact(1)).thenReturn(true);
        when(first.longValue()).thenReturn(Long.MAX_VALUE);
        when(second.advanceExact(0)).thenReturn(true);
        when(second.longValue()).thenReturn(7L);
        var reader = new LeafReader9(wrapped);
        reader.initDocValueIterators(List.of(
            new DocValueFieldInfo.Simple("_version", DocValueFieldInfo.DocValueType.NUMERIC, false)));

        var firstCursor = reader.openNumericValueReader("_version");
        var secondCursor = reader.openNumericValueReader("_version");

        assertNull(firstCursor.get(0));
        assertEquals(Long.MAX_VALUE, firstCursor.get(1));
        assertEquals(7L, secondCursor.get(0));
        verify(wrapped, times(3)).getNumericDocValues("_version");
    }

    @Test
    void tenPreservesMissingValuesAndLongPrecision() throws IOException {
        var wrapped = mock(shadow.lucene10.org.apache.lucene.index.LeafReader.class);
        var values = mock(shadow.lucene10.org.apache.lucene.index.NumericDocValues.class);
        when(wrapped.getNumericDocValues("_version")).thenReturn(values);
        when(values.advanceExact(1)).thenReturn(true);
        when(values.longValue()).thenReturn(Long.MAX_VALUE);

        var cursor = new LeafReader10(wrapped).openNumericValueReader("_version");

        assertNull(cursor.get(0));
        assertEquals(Long.MAX_VALUE, cursor.get(1));
        verify(wrapped, times(1)).getNumericDocValues("_version");
    }

    @Test
    void absentFieldReturnsNullInEveryAdapter() throws IOException {
        var readers = List.of(
            new LeafReader5(mock(shadow.lucene5.org.apache.lucene.index.LeafReader.class)),
            new LeafReader6(mock(shadow.lucene6.org.apache.lucene.index.LeafReader.class)),
            new LeafReader7(mock(shadow.lucene7.org.apache.lucene.index.LeafReader.class)),
            new LeafReader9(mock(shadow.lucene9.org.apache.lucene.index.LeafReader.class)),
            new LeafReader10(mock(shadow.lucene10.org.apache.lucene.index.LeafReader.class)));

        for (LuceneLeafReader reader : readers) {
            assertNull(reader.openNumericValueReader("_version").get(0));
        }
    }
}
