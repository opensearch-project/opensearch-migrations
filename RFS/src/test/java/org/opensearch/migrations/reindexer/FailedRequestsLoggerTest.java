package org.opensearch.migrations.reindexer;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FailedRequestsLoggerTest {
    @Test
    void testLogBulkFailure_withNoBody() {
        try (var logs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER)) {
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
        try (var logs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER)) {
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

    @Test
    void testLoggerNameResolution() {
        // Verify that the logger constant matches the expected name
        assertThat(FailedRequestsLogger.FAILED_REQUESTS_LOGGER, equalTo("FailedRequestsLogger"));
        
        // Verify that a logger created with this name can be resolved
        Logger logger = LoggerFactory.getLogger(FailedRequestsLogger.FAILED_REQUESTS_LOGGER);
        assertThat(logger.getName(), equalTo("FailedRequestsLogger"));
    }

    @Test
    void testCustomLoggerInjection() {
        // Create a custom logger for testing
        try (var logs = new CloseableLogSetup("CustomTestLogger")) {
            Logger customLogger = logs.getTestLogger();
            var logger = new FailedRequestsLogger(customLogger);

            var indexName = "customIndexName";
            var size = 44;
            var body = "<CUSTOM_BULK_REQUEST_BODY>";
            var cause = "<CUSTOM_CAUSE!>";
            logger.logBulkFailure(indexName, () -> size, () -> body, new RuntimeException(cause));

            // Verify the log was captured by the custom logger
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
    void testBulkFailureLogsToCorrectLogger() {
        // Verify that bulk failures log detailed info to the named logger (DLQ) and error summary to class-based logger
        try (var namedLogs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER);
             var classLogs = new CloseableLogSetup(FailedRequestsLogger.class.getName())) {
            
            var logger = new FailedRequestsLogger();

            var indexName = "testIndex";
            var size = 55;
            var body = "<TEST_BULK_REQUEST_BODY>";
            var cause = "<TEST_CAUSE!>";
            logger.logBulkFailure(indexName, () -> size, () -> body, new RuntimeException(cause));

            // Verify the detailed log appears in the named logger (DLQ)
            assertThat(
                namedLogs.getLogEvents(),
                hasItem(
                    allOf(
                        containsString(indexName),
                        containsString(size + ""),
                        containsString(body),
                        containsString(cause)
                    )
                )
            );

            // Verify the error summary appears in the class-based logger
            assertEquals(1, classLogs.getLogEvents().size());
            assertThat(
                classLogs.getLogEvents(),
                hasItem(
                    allOf(
                        containsString(indexName),
                        containsString(size + ""),
                        containsString("DLQ"),
                        containsString("manual investigation and retry")
                    )
                )
            );
        }
    }

    @Test
    void testMultipleInstances() {
        // Verify that multiple instances of FailedRequestsLogger work correctly
        try (var logs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER)) {
            var logger1 = new FailedRequestsLogger();
            var logger2 = new FailedRequestsLogger();

            var indexName1 = "index1";
            var indexName2 = "index2";
            var size = 66;
            var body = "<BODY>";
            var cause = "<CAUSE>";

            logger1.logBulkFailure(indexName1, () -> size, () -> body, new RuntimeException(cause));
            logger2.logBulkFailure(indexName2, () -> size, () -> body, new RuntimeException(cause));

            // Verify both logs appear
            assertThat(logs.getLogEvents(), hasItem(containsString(indexName1)));
            assertThat(logs.getLogEvents(), hasItem(containsString(indexName2)));
            assertEquals(2, logs.getLogEvents().size());
        }
    }
}
