package org.opensearch.migrations.bulkload.common;

import org.opensearch.migrations.reindexer.faileddocumentstream.SplitRequestFailure;

import lombok.Getter;

/** Keeps one logical document failure attached to the exact failed split request. */
@Getter
public final class SplitDocumentException extends RuntimeException {
    private final SplitRequestFailure failure;

    SplitDocumentException(SplitRequestFailure failure, Throwable cause) {
        super("Large document request failed at " + failure.stage()
            + "; replay the whole index operation from its checkpoint", cause);
        this.failure = failure;
    }
}
