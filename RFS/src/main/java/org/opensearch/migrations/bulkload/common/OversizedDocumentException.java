package org.opensearch.migrations.bulkload.common;

/** A transformed operation exceeds an explicit document ceiling or cannot fit the staging protocol. */
public class OversizedDocumentException extends IllegalArgumentException {
    public OversizedDocumentException(long size, long limit, String reason) {
        super("Transformed bulk operation requires " + size + " bytes, exceeding the "
            + limit + " byte limit. " + reason);
    }
}
