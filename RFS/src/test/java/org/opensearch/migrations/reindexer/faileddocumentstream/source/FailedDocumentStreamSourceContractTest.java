package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceContractTest;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

/** Holds the failure-stream source to the same contract as every other source. */
class FailedDocumentStreamSourceContractTest extends DocumentSourceContractTest {

    private static final String PREFIX = "rfs-failed-document-stream/";
    private static final String SESSION_ID = "session-under-test";
    private static final String COLLECTION = "orders-2024";

    private InMemoryFailedDocumentStreamObjectStore store;
    private SessionManifest manifest;

    @Override
    protected DocumentSource newSource() throws Exception {
        if (store == null) {
            store = new InMemoryFailedDocumentStreamObjectStore();
            // Two objects for one worker, so the resume tests cross an object boundary.
            FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION_ID, COLLECTION, "worker-a", 1,
                List.of(
                    FailedDocumentStreamFixtures.record(COLLECTION, "doc-1", FailureClass.NON_RETRYABLE),
                    FailedDocumentStreamFixtures.record(COLLECTION, "doc-2", FailureClass.RETRYABLE_EXHAUSTED)));
            FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION_ID, COLLECTION, "worker-a", 2,
                List.of(
                    FailedDocumentStreamFixtures.record(COLLECTION, "doc-3", FailureClass.NON_RETRYABLE)));
            FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION_ID, COLLECTION, "worker-b", 1,
                List.of(
                    FailedDocumentStreamFixtures.record(COLLECTION, "doc-4", FailureClass.NON_RETRYABLE),
                    FailedDocumentStreamFixtures.record(COLLECTION, "doc-5", FailureClass.NON_RETRYABLE)));
            FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION_ID, "users", "worker-a", 1,
                List.of(
                    FailedDocumentStreamFixtures.record("users", "user-1", FailureClass.NON_RETRYABLE),
                    FailedDocumentStreamFixtures.record("users", "user-2", FailureClass.NON_RETRYABLE)));
            manifest = new SessionSealer(store).seal(PREFIX, SESSION_ID).manifest();
        }
        // A fresh source over the same sealed session.
        return new FailedDocumentStreamSource(store, manifest, List.of(), null, false);
    }

    @Override
    protected String collectionUnderTest() {
        return COLLECTION;
    }
}
