package org.opensearch.migrations.cluster;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotReader_ES_6_8;
import org.opensearch.migrations.bulkload.version_es_7_10.SnapshotReader_ES_7_10;
import org.opensearch.migrations.bulkload.version_os_2_11.RemoteWriter_OS_2_11;
import org.opensearch.migrations.bulkload.version_universal.RemoteReader;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ClusterProviderRegistry {

    /** Ensure we are always getting fresh providers when searching for one */
    private List<VersionSpecificCluster> getProviders() {
        return List.of(
            new SnapshotReader_ES_6_8(),
            new SnapshotReader_ES_7_10(),
            new RemoteWriter_OS_2_11(),
            new RemoteReader()
        );
    }

    /**
     * Gets a snapshot resource provider for the given version and source repo
     * @param version The version of the source cluster
     * @param repo The source repo that contains of the snapshot
     * @return The snapshot resource provider
     */
    public ClusterSnapshotReader getSnapshotReader(Version version, SourceRepo repo) {
        var snapshotProvider = getProviders()
            .stream()
            .filter(p -> p.compatibleWith(version))
            .filter(ClusterSnapshotReader.class::isInstance)
            .map(ClusterSnapshotReader.class::cast)
            .map(p -> p.initialize(version))
            .findFirst()
            .orElseThrow(() -> new UnsupportedVersionException("No snapshot provider found for version: " + version));

        snapshotProvider.initialize(repo);
        log.info("Found snapshot resource reader for version: " + version);
        return snapshotProvider;
    }

    /**
     * Get a remote provider from a connection context
     * @param connection The connection context for the cluster
     * @return The remote resource provider
     */
    public ClusterReader getRemoteReader(ConnectionContext connection) {
        var clientFactory = new OpenSearchClientFactory(null);
        var client = clientFactory.get(connection);
        var version = client.getClusterVersion();

        var remoteProvider = getRemoteProviders(connection)
            .filter(p -> p.compatibleWith(version))
            .filter(ClusterReader.class::isInstance)
            .map(ClusterReader.class::cast)
            .findFirst()
            .orElseThrow(() -> new UnsupportedVersionException("Unable to find compatible reader for " + connection + ", " + version));

        log.info("Found remote reader for version: " + version);
        return remoteProvider;
    }

    /**
     * Get a remote writer from a connection context
     * @param connection The connection context for the cluster
     * @return The remote resource creator
     */
    public ClusterWriter getRemoteWriter(ConnectionContext connection, Version versionOverride, DataFilterArgs dataFilterArgs) {
        var clientFactory = new OpenSearchClientFactory(null);
        var version = Optional.ofNullable(versionOverride)
            .orElseGet(() -> clientFactory.get(connection).getClusterVersion());

        var remoteProvider = getRemoteProviders(connection)
            .filter(p -> p.compatibleWith(version))
            .filter(ClusterWriter.class::isInstance)
            .map(ClusterWriter.class::cast)
            .map(p -> p.initialize(versionOverride))
            .map(p -> p.initialize(dataFilterArgs))
            .findFirst()
            .orElseThrow(() -> new UnsupportedVersionException("Unable to find compatible writer for " + connection + ", " + version));

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
