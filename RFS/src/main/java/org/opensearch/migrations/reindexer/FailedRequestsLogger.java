package org.opensearch.migrations.reindexer;

import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class FailedRequestsLogger {
    public static final String FAILED_REQUESTS_LOGGER = "FailedRequestsLogger";
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private final Logger failedRequestLogger;

    /**
     * Record representing a failed bulk request for DLQ logging.
     * This structure is serialized to JSON for parsing with tools like jq.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkFailureRecord(
        String indexName,
        int failedItemCount,
        String message,
        String requestBody,
        String responseBody
    ) {}

    /**
     * Exception thrown when bulk failure record cannot be serialized to JSON.
     * This exception is sanitized and does not include the request body or the cause
     * (which may contain document content) to avoid propagating sensitive data.
     */
    public static class BulkFailureSerializationException extends RuntimeException {
        public BulkFailureSerializationException(String indexName, int failedItemCount) {
            super(String.format("Failed to serialize bulk failure record to JSON for index '%s' with %d failed items", 
                indexName, failedItemCount));
        }
    }

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
        var requestBody = bulkRequestBodySupplier.get();

        var responseBody = Optional.ofNullable(rootCause)
            .filter(OperationFailed.class::isInstance)
            .map(OperationFailed.class::cast)
            .map(opFailed -> opFailed.response)
            .map(response -> response.body)
            .orElse(null);

        // Log error summary for visibility in main logs (before JSON processing so it always happens)
        log.atError()
            .setCause(error)
            .setMessage("Bulk request failed for {} index on {} items. Response body: {}. Request body logged to DLQ (FailedRequests logger) for manual investigation and retry.")
            .addArgument(indexName)
            .addArgument(failedItemCount)
            .addArgument(responseBody)  // null is fine here, will be displayed as "null"
            .log();

        // Create structured record for DLQ logging
        var failureRecord = new BulkFailureRecord(
            indexName,
            failedItemCount,
            Optional.ofNullable(rootCause).map(Throwable::getMessage).orElse("[NULL]"),
            requestBody,
            responseBody
        );

        // Log to the dedicated failed requests logger (DLQ) as JSON for JQ parsing
        try {
            String jsonRecord = OBJECT_MAPPER.writeValueAsString(failureRecord);
            failedRequestLogger.atInfo().setMessage("{}").addArgument(jsonRecord).log();
        } catch (JsonProcessingException e) {
            log.atError()
                .setCause(e)
                .setMessage("Failed to serialize bulk failure record to JSON for index {}")
                .addArgument(indexName)
                .log();
            // Throw sanitized exception without request body or cause (which may contain document content)
            throw new BulkFailureSerializationException(indexName, failedItemCount);
        }
    }

    private Throwable getRootCause(Throwable error) {
        var currentError = error;
        while (currentError.getCause() != null) {
            currentError = currentError.getCause();
        }
        return currentError;
    }
}
