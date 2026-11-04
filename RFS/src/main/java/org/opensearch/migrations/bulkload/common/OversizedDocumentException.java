package org.opensearch.migrations.bulkload.common;

/** A transformed operation cannot be sent without exceeding the configured HTTP body limit. */
public class OversizedDocumentException extends IllegalArgumentException {
    public OversizedDocumentException(long size, long limit, String reason) {
        super("Transformed bulk operation requires " + size + " bytes, exceeding the "
            + limit + " byte request limit. " + reason);
    }
}
