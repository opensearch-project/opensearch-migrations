package org.opensearch.migrations.reindexer;

import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FailedRequestsLogger {

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
            log.atInfo()
                .setMessage(
                    "Bulk request failed for {} index on {} items, reason {}, bulk request body followed by response:\n{}\n{}"
                )
                .addArgument(indexName)
                .addArgument(failedItemCounter::getAsInt)
                .addArgument(rootCause::getMessage)
                .addArgument(bulkRequestBodySupplier)
                .addArgument(responseBody::get)
                .log();
        } else {
            log.atInfo()
                .setMessage("Bulk request failed for {} index on {} documents, reason {}, bulk request body:\n{}")
                .addArgument(indexName)
                .addArgument(failedItemCounter::getAsInt)
                .addArgument(() -> Optional.ofNullable(rootCause).map(Throwable::getMessage).orElse("[NULL]"))
                .addArgument(bulkRequestBodySupplier)
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
