package org.opensearch.migrations.reindexer;

import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;
import org.opensearch.migrations.reindexer.faileddocumentstream.StagedRequestFailure;

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
        logFailure(indexName, failedItemCounter, bulkRequestBodySupplier, null, null, error);
    }

    public void logLargeDocumentFailure(
        String indexName, String transformedRequestBody, String originalDocument, StagedRequestFailure stagedFailure, Throwable error
    ) {
        logFailure(indexName, () -> 1, () -> transformedRequestBody, originalDocument, stagedFailure, error);
    }

    private void logFailure(
        String indexName, IntSupplier failedItemCounter, Supplier<String> bulkRequestBodySupplier,
        String originalDocument, StagedRequestFailure stagedFailure, Throwable error
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
            .setMessage(stagedFailure != null
                ? "Large document migration failed for {} index on {} document(s). Response body: {}. "
                    + "Full document and failed stage details are in the FailedRequests logger. "
                    + "Reconcile pending publication in the staging journal before replay. With root cause {}"
                : "Bulk request failed for {} index on {} items. Response body: {}." +
                    "Request body logged to failed document stream (FailedRequests logger) for manual investigation and retry." +
                    "With root cause {}")
            .addArgument(indexName)
            .addArgument(failedItemCount)
            .addArgument(responseBody)
            .addArgument(rootCause)
            .log();

        var event = failedRequestLogger.atInfo()
            .addKeyValue(FailedRequestsLoggerKeys.INDEX_NAME, indexName)
            .addKeyValue(FailedRequestsLoggerKeys.FAILED_ITEM_COUNT, failedItemCount)
            .addKeyValue(FailedRequestsLoggerKeys.EXCEPTION_MESSAGE, error != null ? error.getMessage() : null)
            .addKeyValue(FailedRequestsLoggerKeys.ROOT_CAUSE, rootCause)
            .addKeyValue(FailedRequestsLoggerKeys.REQUEST_BODY, bulkRequestBodySupplier.get())
            .addKeyValue(FailedRequestsLoggerKeys.RESPONSE_BODY, responseBody);
        if (originalDocument != null) {
            event.addKeyValue(FailedRequestsLoggerKeys.ORIGINAL_DOCUMENT, originalDocument);
        }
        if (stagedFailure != null) {
            event.addKeyValue(FailedRequestsLoggerKeys.STAGED_FAILURE,
                ObjectMapperFactory.createDefaultMapper().valueToTree(stagedFailure).toString());
        }
        event.log(stagedFailure == null ? "Bulk failure logged to failed document stream"
            : "Large document failure logged to failed document stream");
    }

    @UtilityClass
    public static final class FailedRequestsLoggerKeys {
        public static final String INDEX_NAME = "indexName";
        public static final String FAILED_ITEM_COUNT = "failedItemCount";
        public static final String EXCEPTION_MESSAGE = "exceptionMessage";
        public static final String ROOT_CAUSE = "rootCause";
        public static final String REQUEST_BODY = "requestBody";
        public static final String RESPONSE_BODY = "responseBody";
        public static final String ORIGINAL_DOCUMENT = "originalDocument";
        public static final String STAGED_FAILURE = "stagedFailure";
    }

    private Throwable getRootCause(Throwable error) {
        var currentError = error;
        while (currentError != null && currentError.getCause() != null) {
            currentError = currentError.getCause();
        }
        return currentError;
    }
}
