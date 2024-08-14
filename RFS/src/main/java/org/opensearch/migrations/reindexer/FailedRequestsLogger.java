package org.opensearch.migrations.reindexer;

import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.rfs.common.OpenSearchClient.OperationFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FailedRequestsLogger {
    static final String LOGGER_NAME = "FailedRequestsLogger";
    private final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);

    public void logBulkFailure(
        String indexName,
        IntSupplier failedItemCounter,
        Supplier<String> bulkRequestBodySupplier,
        Throwable error
    ) {
        var rootCause = getRootCause(error);

        var responseBody = Optional.ofNullable(rootCause)
            .filter(OperationFailed.class::isInstance)
            .map(OperationFailed.class::cast)
            .map(opFailed -> opFailed.response)
            .map(response -> response.body);

        if (responseBody.isPresent()) {
            logger.atInfo()
                .setMessage(
                    "Bulk request failed for {} index on {} items, reason {}, bulk request body followed by response:\n{}\n{}"
                )
                .addArgument(indexName)
                .addArgument(failedItemCounter::getAsInt)
                .addArgument(rootCause.getMessage())
                .addArgument(bulkRequestBodySupplier::get)
                .addArgument(responseBody::get)
                .log();
        } else {
            logger.atInfo()
                .setMessage("Bulk request failed for {} index on {} documents, reason {}, bulk request body:\n{}")
                .addArgument(indexName)
                .addArgument(failedItemCounter::getAsInt)
                .addArgument(rootCause.getMessage())
                .addArgument(bulkRequestBodySupplier::get)
                .log();
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
