package org.opensearch.migrations.bulkload.version_es_6_8;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

public class OpenSearchClient_ES_6_8 extends OpenSearchClient {
    public OpenSearchClient_ES_6_8(ConnectionContext connectionContext) {
        super(connectionContext);
    }

    public OpenSearchClient_ES_6_8(RestClient client, FailedRequestsLogger failedRequestsLogger) {
        super(client, failedRequestsLogger);
    }

    protected String getCreateIndexPath(String indexName) {
        return indexName + "?include_type_name=false";
    }

    protected String getBulkRequestPath(String indexName) {
        return indexName + "/_doc/_bulk";
    }
}