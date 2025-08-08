package org.opensearch.migrations.bulkload.version_es_6_8;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenSearchClient_ES_6_8 extends OpenSearchClient {
    public OpenSearchClient_ES_6_8(ConnectionContext connectionContext, Version version, CompressionMode compressionMode) {
        super(connectionContext, version, compressionMode);
        if (version.getFlavor() != Flavor.ELASTICSEARCH && version.getMajor() != 6) {
            log.atWarn().setMessage("OpenSearchClient_ES_6_8 created for cluster with version {}").addArgument(version.toString()).log();
        }
    }

    public OpenSearchClient_ES_6_8(RestClient client, FailedRequestsLogger failedRequestsLogger, Version version, CompressionMode compressionMode) {
        super(client, failedRequestsLogger, version, compressionMode);
        if (version.getFlavor() != Flavor.ELASTICSEARCH && version.getMajor() != 6) {
            log.atWarn().setMessage("OpenSearchClient_ES_6_8 created for cluster with version {}").addArgument(version.toString()).log();
        }
    }

    protected String getCreateIndexPath(String indexName) {
        return indexName + "?include_type_name=false";
    }

    protected String getBulkRequestPath(String indexName) {
        return indexName + "/_doc/_bulk";
    }
}
