package org.opensearch.migrations.bulkload.common;

import org.opensearch.migrations.reindexer.faileddocumentstream.StagedRequestFailure;

import lombok.Getter;

/** Keeps one logical document failure attached to the exact failed staging request. */
@Getter
public final class StagedDocumentException extends RuntimeException {
    private final StagedRequestFailure failure;

    StagedDocumentException(StagedRequestFailure failure, Throwable cause) {
        super("Large document request failed at " + failure.stage() + "; staging journal "
            + failure.stagingIndex() + "/" + failure.journalId()
            + (failure.publicationMayHaveCommitted() ? "; reconcile publication before replay" : ""), cause);
        this.failure = failure;
    }
}
