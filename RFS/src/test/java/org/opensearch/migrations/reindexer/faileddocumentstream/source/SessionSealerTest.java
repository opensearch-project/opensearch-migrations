package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.util.List;

import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionSealerTest {

    private static final String PREFIX = "rfs-failed-document-stream/";
    private static final String SESSION = "session-1";

    private InMemoryFailedDocumentStreamObjectStore store;
    private SessionSealer sealer;

    @BeforeEach
    void setUp() {
        store = new InMemoryFailedDocumentStreamObjectStore();
        sealer = new SessionSealer(store);
    }

    private String putRecords(String index, String worker, int seq, String... documentIds) {
        var records = java.util.Arrays.stream(documentIds)
            .map(id -> FailedDocumentStreamFixtures.record(index, id, FailureClass.NON_RETRYABLE))
            .toList();
        return FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, index, worker, seq, records);
    }

    @Test
    void groupsObjectsByIndexAndWorker() throws IOException {
        var ordersA = putRecords("orders", "worker-a", 1, "doc-1");
        var ordersB = putRecords("orders", "worker-b", 1, "doc-2");
        var users = putRecords("users", "worker-a", 1, "user-1");

        var manifest = sealer.seal(PREFIX, SESSION).manifest();

        assertThat(manifest.collectionNames(), contains("orders", "users"));
        assertThat(manifest.partition("orders", "worker-a").orElseThrow().objectKeys(), contains(ordersA));
        assertThat(manifest.partition("orders", "worker-b").orElseThrow().objectKeys(), contains(ordersB));
        assertThat(manifest.partition("users", "worker-a").orElseThrow().objectKeys(), contains(users));
    }

    @Test
    void aSecondSealerAgreesRatherThanOverwriting() throws IOException {
        putRecords("orders", "worker-a", 1, "doc-1");

        var first = sealer.seal(PREFIX, SESSION);
        var second = new SessionSealer(store).seal(PREFIX, SESSION);

        assertThat("the first caller published", first.publishedByThisCaller(), is(true));
        assertThat("the second found it already there", second.publishedByThisCaller(), is(false));
        assertThat(second.digest(), equalTo(first.digest()));
    }

    @Test
    void refusesToSealASessionThatKeptGrowing() throws IOException {
        putRecords("orders", "worker-a", 1, "doc-1");
        sealer.seal(PREFIX, SESSION);

        // Still writing when the first seal landed.
        putRecords("orders", "worker-c", 1, "doc-9");

        var thrown = assertThrows(SessionSealer.SessionSealMismatchException.class,
            () -> new SessionSealer(store).seal(PREFIX, SESSION));

        assertThat(thrown.getMessage(), containsString("A seal is permanent"));
    }

    @Test
    void theManifestIsNotMistakenForARecordObject() throws IOException {
        putRecords("orders", "worker-a", 1, "doc-1");
        sealer.seal(PREFIX, SESSION);

        // The listing now includes the manifest; treating it as a record would change the digest.
        var second = new SessionSealer(store).seal(PREFIX, SESSION);

        assertThat(second.publishedByThisCaller(), is(false));
    }

    @Test
    void readManifestReturnsNothingForAnUnsealedSession() throws IOException {
        putRecords("orders", "worker-a", 1, "doc-1");

        assertNull(SessionSealer.readManifest(store, PREFIX, SESSION));
    }

    @Test
    void verifyPassesWhenTheListingStillMatchesTheSeal() throws IOException {
        putRecords("orders", "worker-a", 1, "doc-1");
        var manifest = sealer.seal(PREFIX, SESSION).manifest();

        assertDoesNotThrow(() -> SessionSealer.verifyAgainstListing(store, PREFIX, manifest));
    }

    @Test
    void verifyFailsWhenAnObjectWentMissing() throws IOException {
        var key = putRecords("orders", "worker-a", 1, "doc-1");
        var manifest = sealer.seal(PREFIX, SESSION).manifest();
        store.remove(key);

        var thrown = assertThrows(IOException.class,
            () -> SessionSealer.verifyAgainstListing(store, PREFIX, manifest));

        assertThat(thrown.getMessage(), containsString("1 object(s) named by the manifest are gone"));
    }

    @Test
    void verifyFailsWhenAnObjectAppearedAfterSealing() throws IOException {
        putRecords("orders", "worker-a", 1, "doc-1");
        var manifest = sealer.seal(PREFIX, SESSION).manifest();
        putRecords("orders", "worker-d", 1, "doc-late");

        var thrown = assertThrows(IOException.class,
            () -> SessionSealer.verifyAgainstListing(store, PREFIX, manifest));

        assertThat(thrown.getMessage(), containsString("1 object(s) appeared after it was sealed"));
    }

    @Test
    void sealsAnEmptySessionAsEmptyRatherThanFailing() throws IOException {
        // Refusing an empty session is the source's call; it still closed cleanly.
        var manifest = sealer.seal(PREFIX, SESSION).manifest();

        assertThat(manifest.collections(), equalTo(List.of()));
    }
}
