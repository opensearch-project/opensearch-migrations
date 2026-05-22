package org.opensearch.migrations.bulkload.lucene;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RfsTunablesTest {

    @AfterEach
    void clearStopwordProp() {
        System.clearProperty(RfsTunables.POSITION_GAP_STOPWORD_PROP);
    }

    @Test
    void readerParallelismPropHasExpectedName() {
        assertEquals("rfs.reader.parallelism", RfsTunables.READER_PARALLELISM_PROP);
    }

    @Test
    void readerParallelismEnvHasExpectedName() {
        assertEquals("RFS_READER_PARALLELISM", RfsTunables.READER_PARALLELISM_ENV);
    }

    @Test
    void positionGapStopwordPropAndEnvHaveExpectedNames() {
        assertEquals("rfs.position.gap.stopword", RfsTunables.POSITION_GAP_STOPWORD_PROP);
        assertEquals("RFS_POSITION_GAP_STOPWORD", RfsTunables.POSITION_GAP_STOPWORD_ENV);
    }

    @Test
    void positionGapStopwordReturnsTrimmedValueFromSystemProperty() {
        System.setProperty(RfsTunables.POSITION_GAP_STOPWORD_PROP, "  a  ");
        assertEquals("a", RfsTunables.positionGapStopword());
    }

    @Test
    void positionGapStopwordReturnsNullWhenUnset() {
        assertNull(RfsTunables.positionGapStopword());
    }

    @Test
    void positionGapStopwordReturnsNullWhenBlank() {
        System.setProperty(RfsTunables.POSITION_GAP_STOPWORD_PROP, "   ");
        assertNull(RfsTunables.positionGapStopword());
    }
}
