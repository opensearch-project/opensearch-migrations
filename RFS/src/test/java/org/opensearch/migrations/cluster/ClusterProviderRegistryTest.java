package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.version_os_2_11.RemoteWriter_OS_2_11;
import org.opensearch.migrations.cluster.ClusterWriterRegistry.UnsupportedVersionException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class ClusterProviderRegistryTest {

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
}
