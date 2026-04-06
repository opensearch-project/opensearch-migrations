package org.opensearch.migrations.replay.http.retries;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BulkItemErrorClassifierTest {

    private final BulkItemErrorClassifier classifier = new BulkItemErrorClassifier();

    @ParameterizedTest
    @ValueSource(strings = {
        "version_conflict_engine_exception",
        "mapper_parsing_exception",
        "strict_dynamic_mapping_exception",
        "document_missing_exception",
        "action_request_validation_exception",
        "invalid_index_name_exception",
        "routing_missing_exception",
        "illegal_argument_exception",
        "resource_already_exists_exception"
    })
    void testNonRetryableErrors(String errorType) {
        Assertions.assertTrue(classifier.isNonRetryable(errorType));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "unavailable_shards_exception",
        "cluster_block_exception",
        "es_rejected_execution_exception",
        "circuit_breaking_exception",
        "timeout_exception",
        "some_unknown_exception"
    })
    void testRetryableErrors(String errorType) {
        Assertions.assertFalse(classifier.isNonRetryable(errorType));
    }

    @org.junit.jupiter.api.Test
    void testNullErrorType() {
        Assertions.assertFalse(classifier.isNonRetryable(null));
    }
}
