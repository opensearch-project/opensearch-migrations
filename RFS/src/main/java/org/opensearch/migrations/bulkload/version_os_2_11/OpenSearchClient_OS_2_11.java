package org.opensearch.migrations.bulkload.version_os_2_11;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

public class OpenSearchClient_OS_2_11 extends OpenSearchClient {
    public OpenSearchClient_OS_2_11(ConnectionContext connectionContext) {
        super(connectionContext);
    }

    public OpenSearchClient_OS_2_11(RestClient client, FailedRequestsLogger failedRequestsLogger) {
        super(client, failedRequestsLogger);
    }

    protected String getCreateIndexPath(String indexName) {
        return indexName;
    }

    protected String getBulkRequestPath(String indexName) {
        return indexName + "/_bulk";
    }
}
