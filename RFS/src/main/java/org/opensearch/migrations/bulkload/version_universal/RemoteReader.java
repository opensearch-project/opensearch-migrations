package org.opensearch.migrations.bulkload.version_universal;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.GlobalMetadata.Factory;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_es_6_8.RemoteReaderClient_ES_6_8;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.RemoteCluster;

public class RemoteReader implements RemoteCluster, ClusterReader {
    private Version version;
    private RemoteReaderClient client;
    private ConnectionContext connection;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.isES_6_X
            .or(VersionMatchers.isES_7_X)
            .or(VersionMatchers.isOS_1_X)
            .or(VersionMatchers.isOS_2_X)
            .test(version);
    }

    @Override
    public RemoteCluster initialize(ConnectionContext connection) {
        this.connection = connection;
        return this;
    }

    @Override
    public Factory getGlobalMetadata() {
        return new RemoteMetadataFactory(getClient());
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new RemoteIndexMetadataFactory(getClient());
    }

    @Override
    public Version getVersion() {
        if (version == null) {
            // Use a throw away client that will work on any version of the service
            var clientFactory = new OpenSearchClientFactory(null);
            version = clientFactory.get(connection).getClusterVersion();
        }
        return version;
    }

    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Remote Cluster: %s %s", version, connection);
    }

    private RemoteReaderClient getClient() {
        if (client == null) {
            if (VersionMatchers.isES_6_X.test(getVersion())) {
                client = new RemoteReaderClient_ES_6_8(getConnection());
            } else {
                client = new RemoteReaderClient(getConnection());
            }
        }
        return client;
    }

    private ConnectionContext getConnection() {
        if (connection == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return connection;
    }
}
