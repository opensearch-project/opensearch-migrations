package org.opensearch.migrations.bulkload.common;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;

import reactor.core.publisher.Flux;

/**
 * Expands one already transformed, encoded operation into bounded request parts on demand.
 * Parts must be applied in order; completion of the stream is not acknowledgement of the document.
 * The caller owns indexing the initial state, retries, final completion, and the original operation's checkpoint.
 */
@FunctionalInterface
interface OperationSplitter {
    Flux<Part> split(BulkNdjson.EncodedOperation operation, long requestLimit);

    // Sonar treats generated record members as hand-written methods under the repository's Java 11 analysis setting.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    record Part(long sequence, byte[] body) {}
}
