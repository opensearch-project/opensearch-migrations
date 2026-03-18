package org.opensearch.migrations.cluster;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionStrictness;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.version_es_6_8.RemoteWriter_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.RemoteWriter_OS_2_11;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ClusterWriterRegistry {

    private List<VersionSpecificCluster> getProviders() {
        return List.of(
            new RemoteWriter_OS_2_11(),
            new RemoteWriter_ES_6_8()
        );
    }

    /**
     * Get a remote writer from a connection context
     */
    public ClusterWriter getRemoteWriter(ConnectionContext connection, Version versionOverride, DataFilterArgs dataFilterArgs, boolean looseMatch) {
        var version = Optional.ofNullable(versionOverride)
            .orElseGet(() -> new OpenSearchClientFactory(connection).getClusterVersion());

        var remoteProvider = getRemoteProviders(connection)
            .filter(p -> looseMatch ? p.looseCompatibleWith(version) : p.compatibleWith(version))
            .filter(ClusterWriter.class::isInstance)
            .map(ClusterWriter.class::cast)
            .map(p -> p.initialize(versionOverride))
            .map(p -> p.initialize(dataFilterArgs))
            .findFirst()
            .orElseThrow(() -> {
                var message = "Unable to find compatible writer for " + connection + ", " + version;
                if (!looseMatch) {
                    message = message + " " + VersionStrictness.REMEDIATION_MESSAGE;
                }
                return new UnsupportedVersionException(message);
            });

        log.info("Found remote writer for version: " + version);
        return remoteProvider;
    }

    private Stream<RemoteCluster> getRemoteProviders(ConnectionContext connection) {
        return getProviders()
            .stream()
            .filter(RemoteCluster.class::isInstance)
            .map(RemoteCluster.class::cast)
            .map(p -> p.initialize(connection));
    }

    static class UnsupportedVersionException extends RuntimeException {
        public UnsupportedVersionException(String msg) {
            super(msg);
        }
    }
}
