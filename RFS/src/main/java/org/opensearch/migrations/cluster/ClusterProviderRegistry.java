package org.opensearch.migrations.cluster;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionStrictness;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.version_es_1_7.SnapshotReader_ES_1_7;
import org.opensearch.migrations.bulkload.version_es_2_4.SnapshotReader_ES_2_4;
import org.opensearch.migrations.bulkload.version_es_6_8.RemoteWriter_ES_6_8;
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
            new SnapshotReader_ES_1_7(),
            new SnapshotReader_ES_2_4(),
            new SnapshotReader_ES_6_8(),
            new SnapshotReader_ES_7_10(),
            new RemoteWriter_OS_2_11(),
            new RemoteWriter_ES_6_8(),
            new RemoteReader()
        );
    }

    /**
     * Gets a snapshot resource provider for the given version and source repo
     * @param version The version of the source cluster
     * @param repo The source repo that contains of the snapshot
     * @return The snapshot resource provider
     */
    public ClusterSnapshotReader getSnapshotReader(Version version, SourceRepo repo, boolean looseMatch) {
        var snapshotProvider = getProviders()
            .stream()
            .filter(p -> looseMatch ? p.looseCompatibleWith(version) : p.compatibleWith(version))
            .filter(ClusterSnapshotReader.class::isInstance)
            .map(ClusterSnapshotReader.class::cast)
            .map(p -> p.initialize(version))
            .findFirst()
            .orElseThrow(() -> {
                var message = "No snapshot provider found for version: " + version;
                if (!looseMatch) {
                    message = message + " " + VersionStrictness.REMEDIATION_MESSAGE;
                }
                return new UnsupportedVersionException(message);
            });

        snapshotProvider.initialize(repo);
        log.atInfo()
            .setMessage("Found snapshot resource reader {} for version: {}")
            .addArgument(snapshotProvider.getClass().getSimpleName())
            .addArgument(version)
            .log();
        return snapshotProvider;
    }

    /**
     * Gets the SnapshotFileFinder associated with the appropriate SnapshotReader for the given version.
     * This allows you to construct the SourceRepo before instantiating the full SnapshotReader.
     */
    public SnapshotFileFinder getSnapshotFileFinder(Version version, boolean looseMatch) {
        return getProviders()
            .stream()
            .filter(p -> looseMatch ? p.looseCompatibleWith(version) : p.compatibleWith(version))
            .filter(ClusterSnapshotReader.class::isInstance)
            .map(ClusterSnapshotReader.class::cast)
            .map(p -> p.initialize(version))
            .findFirst()
            .map(ClusterSnapshotReader::getSnapshotFileFinder)
            .orElseThrow(() -> {
                var message = "No SnapshotFileFinder found for version: " + version;
                if (!looseMatch) {
                    message = message + " " + VersionStrictness.REMEDIATION_MESSAGE;
                }
                return new UnsupportedVersionException(message);
            });
    }

    /**
     * Get a remote provider from a connection context
     * @param connection The connection context for the cluster
     * @return The remote resource provider
     */
    public ClusterReader getRemoteReader(ConnectionContext connection, boolean looseMatch) {
        var clientFactory = new OpenSearchClientFactory(connection);
        var client = clientFactory.determineVersionAndCreate();
        var version = client.getClusterVersion();

        var remoteProvider = getRemoteProviders(connection)
            .filter(p -> looseMatch ? p.looseCompatibleWith(version) : p.compatibleWith(version))
            .filter(ClusterReader.class::isInstance)
            .map(ClusterReader.class::cast)
            .findFirst()
            .orElseThrow(() -> {
                var message = "Unable to find compatible reader for " + connection + ", " + version;
                if (!looseMatch) {
                    message = message + " " + VersionStrictness.REMEDIATION_MESSAGE;
                }
                return new UnsupportedVersionException(message);
            });

        log.info("Found remote reader for version: " + version);
        return remoteProvider;
    }

    /**
     * Get a remote writer from a connection context
     * @param connection The connection context for the cluster
     * @return The remote resource creator
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
