package org.opensearch.migrations.reindexer;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
class FailedRequestsLoggerTest {

    @Test
    void testLogBulkFailure_withMdcAssertions() {
        try (var logs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER)) {
            var logger = new FailedRequestsLogger(logs.getTestLogger());

            var indexName = "orders";
            var failedItemCount = 1;
            
            var requestBody = """
                {"index":{"_index":"orders","_id":"order-001"}}
                {"customer_id":"cust123","total":150.00,"items":["item1","item2"]}
                """;
            
            var responseBody = """
                {
                  "errors": true,
                  "items": [{
                    "index": {
                      "status": 429,
                      "error": {"type": "es_rejected_execution_exception", "reason": "rejected execution"}
                    }
                  }]
                }
                """;

            var wrappedCause = new RuntimeException(
                "Rate limit exceeded",
                new OperationFailed("Too many requests", new HttpResponse(429, null, null, responseBody))
            );
            
            logger.logBulkFailure(indexName, () -> failedItemCount, () -> requestBody, wrappedCause);

            // Verify the log event was captured
            assertEquals(1, logs.getCapturedLogEvents().size());
            var capturedEvent = logs.getCapturedLogEvents().get(0);
            
            // Verify the log message
            assertThat(capturedEvent.getMessage(), containsString("Bulk failure logged to DLQ"));
            
            // Verify MDC context data contains all expected keys and values
            Map<String, String> mdc = capturedEvent.getContextData();
            assertNotNull(mdc, "MDC context data should be present");
            
            assertEquals(indexName, mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.INDEX_NAME));
            assertEquals("1", mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.FAILED_ITEM_COUNT));
            assertEquals("Rate limit exceeded", mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.EXCEPTION_MESSAGE));
            
            String rootCause = mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.ROOT_CAUSE);
            assertNotNull(rootCause, "rootCause should be present in MDC");
            assertThat(rootCause, containsString("OperationFailed"));
            assertThat(rootCause, containsString("Too many requests"));

            String capturedRequestBody = mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.REQUEST_BODY);
            assertNotNull(capturedRequestBody, "requestBody should be present in MDC");
            assertThat(capturedRequestBody, containsString("order-001"));
            assertThat(capturedRequestBody, containsString("cust123"));

            assertEquals(responseBody.trim(), mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.RESPONSE_BODY).trim());
        }
    }

    @Test
    void testBulkFailureLogsToCorrectLoggers() {
        try (var namedLogs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER);
             var classLogs = new CloseableLogSetup(FailedRequestsLogger.class.getName())) {
            
            var logger = new FailedRequestsLogger(namedLogs.getTestLogger());

            var indexName = "products";
            var failedItemCount = 1;
            var requestBody = """
                {"index":{"_index":"products","_id":"prod-001"}}
                {"name":"Widget","price":19.99}
                """;
            
            logger.logBulkFailure(indexName, () -> failedItemCount, () -> requestBody, 
                new RuntimeException("Connection timeout"));

            // Verify detailed log in named logger (DLQ) with MDC data
            assertEquals(1, namedLogs.getCapturedLogEvents().size());
            var capturedEvent = namedLogs.getCapturedLogEvents().get(0);
            assertThat(capturedEvent.getMessage(), containsString("Bulk failure logged to DLQ"));
            
            Map<String, String> mdc = capturedEvent.getContextData();
            assertEquals(indexName, mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.INDEX_NAME));
            assertEquals("1", mdc.get(FailedRequestsLogger.FailedRequestsLoggerKeys.FAILED_ITEM_COUNT));

            // Verify error summary in class-based logger
            assertEquals(1, classLogs.getLogEvents().size());
            String classLogMessage = classLogs.getLogEvents().get(0);
            assertThat(
                classLogMessage,
                allOf(
                    containsString(indexName),
                    containsString("1"),
                    containsString("DLQ"),
                    containsString("manual investigation and retry")
                )
            );
            
            // Verify class-based logger does NOT contain customer data from the bulk request
            assertThat(classLogMessage, not(containsString("prod-001")));
            assertThat(classLogMessage, not(containsString("Widget")));
            assertThat(classLogMessage, not(containsString("19.99")));
        }
    }
}
