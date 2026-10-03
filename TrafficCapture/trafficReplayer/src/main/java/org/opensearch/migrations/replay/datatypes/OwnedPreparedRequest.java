package org.opensearch.migrations.replay.datatypes;

/**
 * Transaction-scoped ownership of a transformed request.
 */
public interface OwnedPreparedRequest extends AutoCloseable {
    int numByteBufs();

    AttemptPayload newAttempt();

    DiagnosticPayload retainDiagnosticCopy();

    @Override
    void close();
}
