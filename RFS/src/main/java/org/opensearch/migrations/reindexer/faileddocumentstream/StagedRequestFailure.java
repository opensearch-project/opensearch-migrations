package org.opensearch.migrations.reindexer.faileddocumentstream;

/**
 * The bounded protocol request that failed while migrating one complete document. Request and
 * response bodies belong only in the failed-document data channel, never ordinary worker logs.
 * An unknown publication outcome requires reconciliation before replay.
 */
public record StagedRequestFailure(
    String stagingIndex,
    String journalId,
    String uploadId,
    String stage,
    Long chunkSequence,
    String taskId,
    String method,
    String path,
    String requestBody,
    Integer responseStatus,
    String responseBody,
    String errorMessage,
    boolean publicationMayHaveCommitted
) {}
