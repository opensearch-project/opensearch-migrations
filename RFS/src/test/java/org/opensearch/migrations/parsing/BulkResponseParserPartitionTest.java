package org.opensearch.migrations.parsing;

import java.util.List;
import java.util.Set;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Covers {@link BulkResponseParser#partitionItems} — the three-way classification
 * (success / non-retryable / retryable) introduced for the durable RFS DLQ
 * (issue #2975).
 */
class BulkResponseParserPartitionTest {

    private static final String SUCCESS_ITEM =
        "{\"index\":{\"_index\":\"movies\",\"_id\":\"%s\",\"result\":\"created\",\"status\":201}}";

    private static final String NON_RETRYABLE_ITEM =
        "{\"index\":{\"_index\":\"movies\",\"_id\":\"%s\",\"status\":400,"
            + "\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad doc\"}}}";

    private static final String RETRYABLE_ITEM =
        "{\"index\":{\"_index\":\"movies\",\"_id\":\"%s\",\"status\":429,"
            + "\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}}";

    private static final String ALLOWLISTED_ITEM =
        "{\"index\":{\"_index\":\"movies\",\"_id\":\"%s\",\"status\":409,"
            + "\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"dup\"}}}";

    private static String response(String... itemJsons) {
        return "{\"took\":1,\"errors\":true,\"items\":[" + String.join(",", itemJsons) + "]}";
    }

    @Test
    void partitionsAllThreeBuckets() {
        var body = response(
            String.format(SUCCESS_ITEM, "a"),
            String.format(NON_RETRYABLE_ITEM, "b"),
            String.format(RETRYABLE_ITEM, "c"),
            String.format(NON_RETRYABLE_ITEM, "d")
        );

        var partition = BulkResponseParser.partitionItems(body, DocumentExceptionAllowlist.empty());

        assertThat(partition.getSuccessPositions(), contains(0));
        assertThat(partition.getNonRetryableFailures(), hasSize(2));
        assertThat(partition.getNonRetryableFailures().get(0).getPosition(), equalTo(1));
        assertThat(partition.getNonRetryableFailures().get(0).getDocumentId(), equalTo("b"));
        assertThat(partition.getNonRetryableFailures().get(0).getErrorType(), equalTo("mapper_parsing_exception"));
        assertThat(partition.getNonRetryableFailures().get(1).getPosition(), equalTo(3));
        assertThat(partition.getRetryableFailures(), hasSize(1));
        assertThat(partition.getRetryableFailures().get(0).getPosition(), equalTo(2));
        assertThat(partition.getRetryableFailures().get(0).getErrorType(), equalTo("es_rejected_execution_exception"));
    }

    @Test
    void allowedExceptionsCountAsSuccess() {
        var allowlist = new DocumentExceptionAllowlist(Set.of("version_conflict_engine_exception"));
        var body = response(
            String.format(ALLOWLISTED_ITEM, "a"),
            String.format(RETRYABLE_ITEM, "b")
        );

        var partition = BulkResponseParser.partitionItems(body, allowlist);

        assertThat(partition.getSuccessPositions(), contains(0));
        assertThat(partition.getNonRetryableFailures(), empty());
        assertThat(partition.getRetryableFailures(), hasSize(1));
    }

    @Test
    void responseItemIsPreservedVerbatim() {
        var body = response(String.format(NON_RETRYABLE_ITEM, "doc-1"));
        var partition = BulkResponseParser.partitionItems(body, DocumentExceptionAllowlist.empty());

        var item = partition.getNonRetryableFailures().get(0);
        assertThat(item.getResponseItem().path("index").path("_id").asText(), equalTo("doc-1"));
        assertThat(
            item.getResponseItem().path("index").path("error").path("type").asText(),
            equalTo("mapper_parsing_exception")
        );
        // The full error sub-object is retained so investigators see the "reason" too.
        assertThat(
            item.getResponseItem().path("index").path("error").path("reason").asText(),
            equalTo("bad doc")
        );
    }

    @Test
    void unparseableResponseReturnsNullSoCallerRetriesAll() {
        var partition = BulkResponseParser.partitionItems("not json at all", DocumentExceptionAllowlist.empty());
        assertThat(partition, equalTo(null));
    }

    @Test
    void responseWithoutItemsReturnsNull() {
        var partition = BulkResponseParser.partitionItems("{\"took\":1}", DocumentExceptionAllowlist.empty());
        assertThat(partition, equalTo(null));
    }

    @Test
    void unknownErrorTypeIsRetryable() {
        // A type not in BulkDocErrorTypes.NON_RETRYABLE is treated as retryable (transient).
        var weird = "{\"index\":{\"_index\":\"movies\",\"_id\":\"x\",\"status\":503,"
            + "\"error\":{\"type\":\"some_brand_new_exception\",\"reason\":\"who knows\"}}}";
        var partition = BulkResponseParser.partitionItems(response(weird), DocumentExceptionAllowlist.empty());

        assertThat(partition.getNonRetryableFailures(), empty());
        assertThat(partition.getRetryableFailures(), hasSize(1));
        assertThat(
            partition.getRetryableFailures().get(0).getErrorType(),
            equalTo("some_brand_new_exception")
        );
    }
}
