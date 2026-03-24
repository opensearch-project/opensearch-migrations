package org.opensearch.migrations.bulkload.solr;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.ClusterVersionDetector;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.RemoteCluster;

public class SolrRemoteReader implements RemoteCluster, ClusterReader {
    private Version version;
    private ConnectionContext connection;

    @Override
    public boolean compatibleWith(Version version) {
        return UnboundVersionMatchers.anySolr.test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return UnboundVersionMatchers.anySolr.test(version);
    }

    @Override
    public RemoteCluster initialize(ConnectionContext connection) {
        this.connection = connection;
        return this;
    }

    @Override
    public ConnectionContext getConnection() {
        if (connection == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return connection;
    }

    @Override
    public Version getVersion() {
        if (version == null) {
            version = ClusterVersionDetector.detect(connection);
        }
        return version;
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new SolrGlobalMetadataFactory();
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        var uri = connection.getUri();
        var client = new SolrClient(uri.toString());
        return new SolrIndexMetadataFactory(client);
    }

    @Override
    public String getFriendlyTypeName() {
        return "Solr Remote Cluster";
    }

    @Override
    public String toString() {
        return String.format("Solr Remote Cluster: %s %s", version, connection);
    }
}
