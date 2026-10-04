package org.opensearch.migrations.replay.datatypes;

/**
 * Owns the canonical packet representation of one transformed replay request.
 *
 * <p>The prepared form is the root from which independently retained target-attempt and diagnostic
 * payloads are created. Each derived payload has its own close boundary, allowing retries and evidence
 * collection to proceed without sharing one fragile buffer lifetime.</p>
 *
 * <p>Closing the prepared request releases only its root ownership. Any attempt or diagnostic copy that
 * was already retained remains valid until that object is closed, which makes the otherwise implicit
 * Netty reference-counting relationships explicit in the replay model.</p>
 */
public interface OwnedPreparedRequest extends AutoCloseable {
    int numByteBufs();

    AttemptPayload newAttempt();

    DiagnosticPayload retainDiagnosticCopy();

    @Override
    void close();
}
