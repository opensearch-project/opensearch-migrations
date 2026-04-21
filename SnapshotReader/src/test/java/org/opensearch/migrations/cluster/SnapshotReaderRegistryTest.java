package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.version_es_1_7.SnapshotReader_ES_1_7;
import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotReader_ES_6_8;
import org.opensearch.migrations.bulkload.version_es_7_10.SnapshotReader_ES_7_10;
import org.opensearch.migrations.bulkload.version_es_9_0.SnapshotReader_ES_9_0;

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

    @Test
    void testGetSnapshotReader_ES_9_0() {
        var sourceRepo = mock(SourceRepo.class);

        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 9.0.8"), sourceRepo, false);

        assertThat(reader, instanceOf(SnapshotReader_ES_9_0.class));
    }

    @Test
    void testGetSnapshotReader_ES_9_1() {
        var sourceRepo = mock(SourceRepo.class);

        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("ES 9.1.5"), sourceRepo, false);

        assertThat(reader, instanceOf(SnapshotReader_ES_9_0.class));
    }

    @Test
    void testGetSnapshotReader_OS_3_0_routesToES9Reader() {
        // Regression guard: OS 3.x must NOT be swallowed by the ES 7.10 looseCompatibleWith
        // (which otherwise claims anyOS). The ES 9 reader compatibleWith() claims isOS_3_X
        // and must be registered BEFORE ES_7_10 for this test to pass.
        var sourceRepo = mock(SourceRepo.class);

        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("OS 3.0.0"), sourceRepo, false);

        assertThat(reader, instanceOf(SnapshotReader_ES_9_0.class));
    }

    @Test
    void testGetSnapshotReader_OS_2_X_stillRoutesToES7_10() {
        // Regression guard: OS 2.x must continue to route through the Lucene 9 reader.
        var sourceRepo = mock(SourceRepo.class);

        var reader = SnapshotReaderRegistry.getSnapshotReader(Version.fromString("OS 2.11.0"), sourceRepo, false);

        assertThat(reader, instanceOf(SnapshotReader_ES_7_10.class));
    }
}
