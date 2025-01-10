package org.opensearch.migrations.bulkload.common;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.version_es_6_8.OpenSearchClient_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

@RequiredArgsConstructor
@Slf4j
public class OpenSearchClientFactory {
    // Version can be null, and if so, the "default" client, for OS_2_11 will be provided (matching the pre-factory behavior)
    private final Version version;

    public OpenSearchClient get(
        ConnectionContext connectionContext
    ) {
        if (version == null || VersionMatchers.isOS_1_X.test(version) || VersionMatchers.isOS_2_X.test(version) || VersionMatchers.isES_7_X.test(version)) {
            return new OpenSearchClient_OS_2_11(connectionContext);
        } else if (VersionMatchers.isES_6_X.test(version)) {
            return new OpenSearchClient_ES_6_8(connectionContext);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }

    public OpenSearchClient get(
            RestClient client,
            FailedRequestsLogger failedRequestsLogger
    ) {
        if (version == null || VersionMatchers.isOS_1_X.test(version) || VersionMatchers.isOS_2_X.test(version) || VersionMatchers.isES_7_X.test(version)) {
            return new OpenSearchClient_OS_2_11(client, failedRequestsLogger);
        } else if (VersionMatchers.isES_6_X.test(version)) {
            return new OpenSearchClient_ES_6_8(client, failedRequestsLogger);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }


}
