package com.rfs.version_os_2_11;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.cluster.ClusterWriter;
import org.opensearch.migrations.cluster.RemoteCluster;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.IndexCreator;

import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContext;
import com.rfs.models.DataFilterArgs;

public class RemoteWriter_OS_2_11 implements RemoteCluster, ClusterWriter {
    private Version version;
    private OpenSearchClient client;
    private ConnectionContext connection;
    private DataFilterArgs dataFilterArgs;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.isOS_2_X.test(version);
    }

    @Override
    public void initialize(DataFilterArgs dataFilterArgs) {
        this.dataFilterArgs = dataFilterArgs;
    }

    @Override
    public void initialize(ConnectionContext connection) {
        this.connection = connection;
    }

    @Override
    public GlobalMetadataCreator getGlobalMetadataCreator() {
        return new GlobalMetadataCreator_OS_2_11(
            getClient(),
            getDataFilterArgs().indexTemplateAllowlist,
            getDataFilterArgs().componentTemplateAllowlist,
            getDataFilterArgs().indexTemplateAllowlist);
    }

    @Override
    public IndexCreator getIndexCreator() {
        return new IndexCreator_OS_2_11(getClient());
    }

    @Override
    public Version getVersion() {
        if (version == null) {
            version = getClient().getClusterVersion();
        }
        return version;
    }

    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Remote Cluster: %s %s", version, connection);
    }

    private OpenSearchClient getClient() {
        if (client == null) {
            client = new OpenSearchClient(getConnection());
        }
        return client;
    }

    private ConnectionContext getConnection() {
        if (connection == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return connection;
    }

    private DataFilterArgs getDataFilterArgs() {
        if (dataFilterArgs == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return dataFilterArgs;
    } 
}
