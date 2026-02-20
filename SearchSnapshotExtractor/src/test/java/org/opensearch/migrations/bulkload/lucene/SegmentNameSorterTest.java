package org.opensearch.migrations.bulkload.lucene;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentNameSorterTest {

    private LuceneLeafReader mockReader(String segmentName) {
        var reader = mock(LuceneLeafReader.class);
        when(reader.getSegmentName()).thenReturn(segmentName);
        when(reader.getContextString()).thenReturn("mock-context");
        return reader;
    }

    @Test
    void sortsSegmentsByName() {
        var r1 = mockReader("_0");
        var r2 = mockReader("_1");
        var r3 = mockReader("_2");

        List<LuceneLeafReader> readers = Arrays.asList(r3, r1, r2);
        readers.sort(SegmentNameSorter.INSTANCE);

        assertEquals("_0", readers.get(0).getSegmentName());
        assertEquals("_1", readers.get(1).getSegmentName());
        assertEquals("_2", readers.get(2).getSegmentName());
    }

    @Test
    void handlesAlphanumericSegmentNames() {
        var ra = mockReader("_a");
        var rb = mockReader("_b");
        var rc = mockReader("_c");

        List<LuceneLeafReader> readers = Arrays.asList(rc, ra, rb);
        readers.sort(SegmentNameSorter.INSTANCE);

        assertEquals("_a", readers.get(0).getSegmentName());
        assertEquals("_b", readers.get(1).getSegmentName());
        assertEquals("_c", readers.get(2).getSegmentName());
    }

    @Test
    void singleElementListUnchanged() {
        var r1 = mockReader("_0");
        List<LuceneLeafReader> readers = Arrays.asList(r1);
        readers.sort(SegmentNameSorter.INSTANCE);
        assertEquals("_0", readers.get(0).getSegmentName());
    }

    @Test
    void reverseOrderIsSorted() {
        var r1 = mockReader("_0");
        var r2 = mockReader("_1");
        var r3 = mockReader("_2");
        var r4 = mockReader("_3");

        List<LuceneLeafReader> readers = Arrays.asList(r4, r3, r2, r1);
        readers.sort(SegmentNameSorter.INSTANCE);

        assertEquals("_0", readers.get(0).getSegmentName());
        assertEquals("_1", readers.get(1).getSegmentName());
        assertEquals("_2", readers.get(2).getSegmentName());
        assertEquals("_3", readers.get(3).getSegmentName());
    }

    @Test
    void comparatorReturnsNegativeForLesser() {
        var r1 = mockReader("_0");
        var r2 = mockReader("_1");
        assertTrue(SegmentNameSorter.INSTANCE.compare(r1, r2) < 0);
    }

    @Test
    void comparatorReturnsPositiveForGreater() {
        var r1 = mockReader("_1");
        var r2 = mockReader("_0");
        assertTrue(SegmentNameSorter.INSTANCE.compare(r1, r2) > 0);
    }
}
