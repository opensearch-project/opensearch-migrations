package org.opensearch.migrations.cluster;

import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionStrictness;
import org.opensearch.migrations.bulkload.common.ClusterVersionDetector;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.version_es_1_7.SnapshotReader_ES_1_7;
import org.opensearch.migrations.bulkload.version_es_2_4.SnapshotReader_ES_2_4;
import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotReader_ES_6_8;
import org.opensearch.migrations.bulkload.version_es_7_10.SnapshotReader_ES_7_10;
import org.opensearch.migrations.bulkload.version_universal.RemoteReader;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class SnapshotReaderRegistry {

    /** Ensure we are always getting fresh providers when searching for one */
    private List<VersionSpecificCluster> getProviders() {
        return List.of(
            new SnapshotReader_ES_1_7(),
            new SnapshotReader_ES_2_4(),
            new SnapshotReader_ES_6_8(),
            new SnapshotReader_ES_7_10(),
            new RemoteReader()
        );
    }

    /**
     * Gets a snapshot resource provider for the given version and source repo
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
     * Get a remote reader from a connection context
     */
    public ClusterReader getRemoteReader(ConnectionContext connection, boolean looseMatch) {
        var version = ClusterVersionDetector.detect(connection);

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

        log.atInfo().setMessage("Found remote reader for version: {}").addArgument(version).log();
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
