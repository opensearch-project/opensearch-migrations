package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotReader_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.RemoteWriter_OS_2_11;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

public class ClusterProviderRegistryTest {

    @Test
    void testGetRemoteWriter_overridden() {
        var connectionContext = mock(ConnectionContext.class);
        var dataFilterArgs = mock(DataFilterArgs.class);

        var writer = ClusterProviderRegistry.getRemoteWriter(connectionContext, Version.fromString("OS 2.15"), dataFilterArgs);

        assertThat(writer, instanceOf(RemoteWriter_OS_2_11.class));
    }

    @Test
    void testGetSnapshotReader_ES_6_4() {
        var sourceRepo = mock(SourceRepo.class);

        var reader = ClusterProviderRegistry.getSnapshotReader(Version.fromString("ES 6.4"), sourceRepo);

        assertThat(reader, instanceOf(SnapshotReader_ES_6_8.class));
    }
}
