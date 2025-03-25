package org.opensearch.migrations.bulkload.version_es_5_6;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenSearchClient_ES_5_6 extends OpenSearchClient {
    public OpenSearchClient_ES_5_6(ConnectionContext connectionContext, Version version) {
        super(connectionContext, version);
        if (version.getFlavor() != Flavor.ELASTICSEARCH && version.getMajor() != 5) {
            log.atWarn().setMessage("OpenSearchClient_ES_5_6 created for cluster with version {}").addArgument(version.toString()).log();
        }
    }

    public OpenSearchClient_ES_5_6(RestClient client, FailedRequestsLogger failedRequestsLogger, Version version) {
        super(client, failedRequestsLogger, version);
        if (version.getFlavor() != Flavor.ELASTICSEARCH && version.getMajor() != 5) {
            log.atWarn().setMessage("OpenSearchClient_ES_5_6 created for cluster with version {}").addArgument(version.toString()).log();
        }
    }

    protected String getCreateIndexPath(String indexName) {
        return indexName;
    }

    protected String getBulkRequestPath(String indexName) {
        return indexName + "/doc/_bulk";
    }
}
