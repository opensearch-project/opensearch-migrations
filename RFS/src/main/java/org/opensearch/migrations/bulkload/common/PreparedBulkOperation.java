package org.opensearch.migrations.bulkload.common;

import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;

/**
 * An operation paired with the exact NDJSON bytes used for request sizing and sending.
 */
@SuppressWarnings({"java:S100", "java:S1172"})
record PreparedBulkOperation(
    Supplier<BulkOperationSpec> operation,
    BulkNdjson.EncodedOperation encoded
) {}
