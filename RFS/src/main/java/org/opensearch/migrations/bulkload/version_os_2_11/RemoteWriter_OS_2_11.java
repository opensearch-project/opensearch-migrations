package org.opensearch.migrations.bulkload.version_os_2_11;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.cluster.ClusterWriter;
import org.opensearch.migrations.cluster.RemoteCluster;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.IndexCreator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RemoteWriter_OS_2_11 implements RemoteCluster, ClusterWriter {
    private Version version;
    private OpenSearchClient client;
    private ConnectionContext connection;
    private DataFilterArgs dataFilterArgs;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.anyOS
            .test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return UnboundVersionMatchers.anyOS
            .or(VersionMatchers.isES_7_X)
            .test(version);
    }

    @Override
    public ClusterWriter initialize(Version versionOverride) {
        if (versionOverride != null) {
            log.warn("Overriding version for cluster, " + versionOverride);
            this.version = versionOverride;
        }
        return this;
    }

    @Override
    public ClusterWriter initialize(DataFilterArgs dataFilterArgs) {
        this.dataFilterArgs = dataFilterArgs;
        return this;
    }

    @Override
    public RemoteCluster initialize(ConnectionContext connection) {
        this.connection = connection;
        return this;
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

    @Override
    public AwarenessAttributeSettings getAwarenessAttributeSettings() {
        return getClient().getAwarenessAttributeSettings();
    }

    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Remote Cluster: %s %s", version, connection);
    }

    private OpenSearchClient getClient() {
        if (client == null) {
            var clientFactory = new OpenSearchClientFactory(getConnection());
            client = clientFactory.determineVersionAndCreate();
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
