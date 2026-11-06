package org.opensearch.migrations.reindexer.faileddocumentstream;

/**
 * The bounded protocol request that failed while migrating one complete document. Request and
 * response bodies belong only in the failed-document data channel, never ordinary worker logs.
 * Replay starts from the complete original operation, never from the failed part alone.
 */
@SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
public record SplitRequestFailure(
    String attemptId,
    String stage,
    long partSequence,
    String method,
    String path,
    String requestBody,
    Integer responseStatus,
    String responseBody,
    String errorMessage,
    boolean completionMayHaveCommitted
) {}
