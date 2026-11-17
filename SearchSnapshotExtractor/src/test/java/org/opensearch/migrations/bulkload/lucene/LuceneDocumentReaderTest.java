package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.version_10.LeafReader10;
import org.opensearch.migrations.bulkload.lucene.version_9.LeafReader9;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LuceneDocumentReaderTest {
    @Test
    void nineOpensStoredFieldsOncePerIndependentDocumentReader() throws IOException {
        var wrapped = mock(shadow.lucene9.org.apache.lucene.index.LeafReader.class);
        var first = mock(shadow.lucene9.org.apache.lucene.index.StoredFields.class);
        var second = mock(shadow.lucene9.org.apache.lucene.index.StoredFields.class);
        when(wrapped.storedFields()).thenReturn(first, second);
        var leaf = new LeafReader9(wrapped);

        var firstReader = leaf.openDocumentReader();
        var secondReader = leaf.openDocumentReader();
        firstReader.document(9);
        firstReader.document(1);
        secondReader.document(0);

        verify(wrapped, times(2)).storedFields();
        verify(first).document(9);
        verify(first).document(1);
        verify(second).document(0);
    }

    @Test
    void tenOpensStoredFieldsOncePerIndependentDocumentReader() throws IOException {
        var wrapped = mock(shadow.lucene10.org.apache.lucene.index.LeafReader.class);
        var first = mock(shadow.lucene10.org.apache.lucene.index.StoredFields.class);
        var second = mock(shadow.lucene10.org.apache.lucene.index.StoredFields.class);
        when(wrapped.storedFields()).thenReturn(first, second);
        var leaf = new LeafReader10(wrapped);

        var firstReader = leaf.openDocumentReader();
        var secondReader = leaf.openDocumentReader();
        firstReader.document(9);
        firstReader.document(1);
        secondReader.document(0);

        verify(wrapped, times(2)).storedFields();
        verify(first).document(9);
        verify(first).document(1);
        verify(second).document(0);
    }
}
