package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.version_es_1_7.SnapshotReader_ES_1_7;
import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotReader_ES_6_8;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class SnapshotReaderRegistryTest {

    @Test
    void testGetSnapshotReader_ES_6_4() {
        var sourceRepo = mock(SourceRepo.class);

        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 6.4"), sourceRepo, false);

        assertThat(reader, instanceOf(SnapshotReader_ES_6_8.class));
    }

    @Test
    void testGetSnapshotReader_matchStrictly_NotFound() {
        var sourceRepo = mock(SourceRepo.class);

        assertThrows(SnapshotReaderRegistry.UnsupportedVersionException.class,
            () -> SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 0"), sourceRepo, false));
    }

    @Test
    void testGetSnapshotReader_matchLoosely() {
        var sourceRepo = mock(SourceRepo.class);

        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 0"), sourceRepo, true);

        assertThat(reader, instanceOf(SnapshotReader_ES_1_7.class));
    }
}
