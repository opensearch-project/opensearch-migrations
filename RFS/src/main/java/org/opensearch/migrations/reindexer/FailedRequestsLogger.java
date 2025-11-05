package org.opensearch.migrations.reindexer;

import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class FailedRequestsLogger {
    public static final String FAILED_REQUESTS_LOGGER = "FailedRequestsLogger";
    private final Logger failedRequestLogger;

    public FailedRequestsLogger() {
        this(null);
    }

    public FailedRequestsLogger(Logger failedRequestLogger) {
        this.failedRequestLogger = failedRequestLogger != null ? failedRequestLogger : LoggerFactory.getLogger(FAILED_REQUESTS_LOGGER);
    }

    public void logBulkFailure(
        String indexName,
        IntSupplier failedItemCounter,
        Supplier<String> bulkRequestBodySupplier,
        Throwable error
    ) {
        var rootCause = getRootCause(error);
        var failedItemCount = failedItemCounter.getAsInt();

        var responseBody = Optional.ofNullable(rootCause)
            .filter(OperationFailed.class::isInstance)
            .map(OperationFailed.class::cast)
            .map(opFailed -> opFailed.response)
            .map(response -> response.body)
            .orElse(null);

        // Do not include failedItems in log to prevent customer data leakage
        log.atError()
            .setCause(error)
            .setMessage("Bulk request failed for {} index on {} items. Response body: {}." +
                    "Request body logged to DLQ (FailedRequests logger) for manual investigation and retry." +
                    "With root cause {}")
            .addArgument(indexName)
            .addArgument(failedItemCount)
            .addArgument(responseBody)
            .addArgument(rootCause)
            .log();

        failedRequestLogger.atInfo()
            .addKeyValue(FailedRequestsLoggerKeys.INDEX_NAME, indexName)
            .addKeyValue(FailedRequestsLoggerKeys.FAILED_ITEM_COUNT, failedItemCount)
            .addKeyValue(FailedRequestsLoggerKeys.EXCEPTION_MESSAGE, error != null ? error.getMessage() : null)
            .addKeyValue(FailedRequestsLoggerKeys.ROOT_CAUSE, rootCause)
            .addKeyValue(FailedRequestsLoggerKeys.REQUEST_BODY, bulkRequestBodySupplier.get())
            .addKeyValue(FailedRequestsLoggerKeys.RESPONSE_BODY, responseBody)
            .log("Bulk failure logged to DLQ");
    }

    @UtilityClass
    public static final class FailedRequestsLoggerKeys {
        public static final String INDEX_NAME = "indexName";
        public static final String FAILED_ITEM_COUNT = "failedItemCount";
        public static final String EXCEPTION_MESSAGE = "exceptionMessage";
        public static final String ROOT_CAUSE = "rootCause";
        public static final String REQUEST_BODY = "requestBody";
        public static final String RESPONSE_BODY = "responseBody";
    }

    private Throwable getRootCause(Throwable error) {
        var currentError = error;
        while (currentError.getCause() != null) {
            currentError = currentError.getCause();
        }
        return currentError;
    }
}
