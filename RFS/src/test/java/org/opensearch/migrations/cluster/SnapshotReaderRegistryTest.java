package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.version_es_1_7.SnapshotReader_ES_1_7;
import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotReader_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.RemoteWriter_OS_2_11;
import org.opensearch.migrations.cluster.ClusterWriterRegistry.UnsupportedVersionException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class SnapshotReaderRegistryTest {

    // --- Writer tests (ClusterWriterRegistry) ---

    @Test
    void testGetRemoteWriter_overridden() {
        var connectionContext = mock(ConnectionContext.class);
        var dataFilterArgs = mock(DataFilterArgs.class);

        var writer = ClusterWriterRegistry.getRemoteWriter(connectionContext, Version.fromString("OS 2.15"), dataFilterArgs, false);

        assertThat(writer, instanceOf(RemoteWriter_OS_2_11.class));
    }

    @Test
    void testGetRemoteWriter_matchStrictly_NotFound() {
        var connectionContext = mock(ConnectionContext.class);
        var dataFilterArgs = mock(DataFilterArgs.class);

        assertThrows(UnsupportedVersionException.class,
            () -> ClusterWriterRegistry.getRemoteWriter(connectionContext, Version.fromString("OS 9999"), dataFilterArgs, false));
    }

    @Test
    void testGetRemoteWriter_matchLoosely() {
        var connectionContext = mock(ConnectionContext.class);
        var dataFilterArgs = mock(DataFilterArgs.class);

        var writer = ClusterWriterRegistry.getRemoteWriter(connectionContext, Version.fromString("OS 9999"), dataFilterArgs, true);

        assertThat(writer, instanceOf(RemoteWriter_OS_2_11.class));
    }

    // --- Reader tests (SnapshotReaderRegistry) ---

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
