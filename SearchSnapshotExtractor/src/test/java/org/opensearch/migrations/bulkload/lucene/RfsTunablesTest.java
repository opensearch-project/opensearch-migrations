package org.opensearch.migrations.bulkload.lucene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RfsTunablesTest {

    @Test
    void readerParallelismPropHasExpectedName() {
        assertEquals("rfs.reader.parallelism", RfsTunables.READER_PARALLELISM_PROP);
    }

    @Test
    void readerParallelismEnvHasExpectedName() {
        assertEquals("RFS_READER_PARALLELISM", RfsTunables.READER_PARALLELISM_ENV);
    }
}
