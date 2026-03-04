package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class SnapshotReaderRegistryTest {

    @Test
    void getSnapshotReader_strictMatch() {
        var sourceRepo = mock(SourceRepo.class);
        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 6.4"), sourceRepo, false);
        assertThat(reader, notNullValue());
    }

    @Test
    void getSnapshotReader_strictMatch_unsupported() {
        var sourceRepo = mock(SourceRepo.class);
        assertThrows(SnapshotReaderRegistry.UnsupportedVersionException.class,
            () -> SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 0"), sourceRepo, false));
    }

    @Test
    void getSnapshotReader_looseMatch() {
        var sourceRepo = mock(SourceRepo.class);
        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 0"), sourceRepo, true);
        assertThat(reader, notNullValue());
    }

    @Test
    void getSnapshotFileFinder_strictMatch() {
        var finder = SnapshotReaderRegistry.getSnapshotFileFinder(Version.fromString("ES 7.10"), false);
        assertThat(finder, notNullValue());
    }

    @Test
    void getSnapshotFileFinder_strictMatch_unsupported() {
        assertThrows(SnapshotReaderRegistry.UnsupportedVersionException.class,
            () -> SnapshotReaderRegistry.getSnapshotFileFinder(Version.fromString("ES 0"), false));
    }
}
