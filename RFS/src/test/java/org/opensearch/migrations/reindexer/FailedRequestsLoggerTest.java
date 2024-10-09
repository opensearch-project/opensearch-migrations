package org.opensearch.migrations.reindexer;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class FailedRequestsLoggerTest {
    @Test
    void testLogBulkFailure_withNoBody() {
        try (var logs = new CloseableLogSetup(FailedRequestsLogger.class.getName())) {
            var logger = new FailedRequestsLogger();

            var indexName = "myIndexName";
            var size = 22;
            var body = "<BULK_REQUEST_BODY>";
            var cause = "<CAUSE!>";
            logger.logBulkFailure(indexName, () -> size, () -> body, new RuntimeException(cause));

            assertThat(
                logs.getLogEvents(),
                hasItem(
                    allOf(
                        containsString(indexName),
                        containsString(size + ""),
                        containsString(body),
                        containsString(cause)
                    )
                )
            );
        }
    }

    @Test
    void testLogBulkFailure_withResponseBody() {
        try (var logs = new CloseableLogSetup(FailedRequestsLogger.class.getName())) {
            var logger = new FailedRequestsLogger();

            var indexName = "yourIndexName";
            var size = 33;
            var requestBody = "<BULK_REQUEST_BODY>";
            var responseBody = "<BULK_RESPONSE_BODY>";

            var topLevelMessage = "Retries limit reached";
            var operationFailureMessage = "Bulk request failed.";
            var wrappedCause = new RuntimeException(
                topLevelMessage,
                new OperationFailed(operationFailureMessage, new HttpResponse(0, null, null, responseBody))
            );
            logger.logBulkFailure(indexName, () -> size, () -> requestBody, wrappedCause);

            assertThat(
                logs.getLogEvents(),
                hasItem(
                    allOf(
                        containsString(indexName),
                        containsString(size + ""),
                        containsString(requestBody),
                        containsString(responseBody),
                        not(containsString(topLevelMessage)),
                        containsString(operationFailureMessage)
                    )
                )
            );
        }
    }
}
