package org.opensearch.migrations.bulkload.pipeline.provider;

import java.nio.file.Paths;
import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamFixtures;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamObjectStore;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamRecordTransformer;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.InMemoryFailedDocumentStreamObjectStore;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.SessionSealer;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailedDocumentStreamSourceProviderTest {

    private static final String BUCKET = "failure-bucket";
    private static final String PREFIX = "rfs-failed-document-stream";
    private static final String STREAM_URI = "s3://" + BUCKET + "/" + PREFIX;
    private static final String SESSION = "session-1";
    private static final String ORDERS = "orders-2024";

    private InMemoryFailedDocumentStreamObjectStore store;
    private FailedDocumentStreamSourceProvider provider;

    private static final SourceRuntime RUNTIME = new SourceRuntime(
        Paths.get("/tmp"), Paths.get("/tmp"), () -> null);

    @BeforeEach
    void setUp() {
        store = new InMemoryFailedDocumentStreamObjectStore();
        provider = new FailedDocumentStreamSourceProvider() {
            @Override
            protected FailedDocumentStreamObjectStore openStore(FailedDocumentStreamSourceSpec spec) {
                // The provider closes what it opens; these tests reuse one store.
                return new InMemoryStoreHandle(store);
            }
        };
    }

    private void putRecords(String index, String worker) {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX + "/", SESSION, index, worker, 1,
            List.of(FailedDocumentStreamFixtures.record(index, "doc-1", FailureClass.NON_RETRYABLE)));
    }

    private FailedDocumentStreamSourceSpec spec(List<String> indexAllowlist, List<String> failureClasses) {
        return new FailedDocumentStreamSourceSpec(
            STREAM_URI, SESSION, "us-east-1", null, indexAllowlist, failureClasses);
    }

    @Test
    void refusesAnUnsealedSession() {
        putRecords(ORDERS, "worker-a");

        var thrown = assertThrows(IllegalArgumentException.class,
            () -> provider.validate(spec(List.of(), List.of()), RUNTIME));

        assertThat(thrown.getMessage(), containsString("has not been sealed"));
        assertThat("names the command that fixes it",
            thrown.getMessage(), containsString("failed-document-stream seal"));
    }

    @Test
    void refusesASealThatNoLongerDescribesTheSession() throws Exception {
        putRecords(ORDERS, "worker-a");
        new SessionSealer(store).seal(PREFIX + "/", SESSION);
        // Still writing when the session was sealed.
        putRecords(ORDERS, "worker-late");

        var thrown = assertThrows(RuntimeException.class,
            () -> provider.validate(spec(List.of(), List.of()), RUNTIME));

        assertThat(thrown.getCause().getMessage(), containsString("does not match its seal"));
    }

    @Test
    void refusesASessionWithNothingToRedrive() throws Exception {
        new SessionSealer(store).seal(PREFIX + "/", SESSION);

        var thrown = assertThrows(IllegalArgumentException.class,
            () -> provider.validate(spec(List.of(), List.of()), RUNTIME));

        assertThat(thrown.getMessage(), containsString("nothing to redrive"));
    }

    @Test
    void refusesAStreamThatIsNotInS3() {
        var local = new FailedDocumentStreamSourceSpec(
            "file:///tmp/stream", SESSION, null, null, List.of(), List.of());

        var thrown = assertThrows(IllegalArgumentException.class, () -> provider.validate(local, RUNTIME));

        assertThat(thrown.getMessage(), containsString("must be given as an s3:// URI"));
    }

    @Test
    void refusesAFailureClassItDoesNotKnow() {
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> provider.validate(spec(List.of(), List.of("NOT_A_CLASS")), RUNTIME));

        assertThat(thrown.getMessage(), containsString("Unknown failure class 'NOT_A_CLASS'"));
    }

    @Test
    void acceptsASealedSessionAndOpensIt() throws Exception {
        putRecords(ORDERS, "worker-a");
        putRecords("users", "worker-a");
        new SessionSealer(store).seal(PREFIX + "/", SESSION);
        var spec = spec(List.of(ORDERS), List.of("NON_RETRYABLE"));

        provider.validate(spec, RUNTIME);
        try (var source = provider.create(spec, RUNTIME)) {
            assertThat(source.listCollections(), contains(ORDERS));
        }
    }

    @Test
    void declaresTheTransformItsRecordsCannotBeWrittenWithout() {
        var required = provider.requiredPreTransform(spec(List.of(), List.of()));

        assertThat(required.isPresent(), is(true));
        assertThat(required.get().get(), instanceOf(FailedDocumentStreamRecordTransformer.class));
    }

    @Test
    void needsNeitherScratchNorWorkingDirectory() {
        // Streamed as read, so a redrive worker needs no volume.
        assertThat(provider.requiresScratchDirectory(spec(List.of(), List.of())), is(false));
        assertThat(provider.requiresWorkingDirectory(spec(List.of(), List.of())), is(false));
    }

    @Test
    void specRoundTripsThroughItsJson() {
        var original = spec(List.of(ORDERS), List.of("NON_RETRYABLE"));

        assertThat(FailedDocumentStreamSourceSpec.fromJson(original.toJson()), equalTo(original));
    }

    @Test
    void specRequiresAStreamAndASession() {
        var empty = JsonNodeFactory.instance.objectNode();

        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamSourceSpec.fromJson(empty));
        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamSourceSpec.fromJson(
            JsonNodeFactory.instance.objectNode().put("streamUri", STREAM_URI)));
    }

    /** Delegates everything but {@code close}. */
    private record InMemoryStoreHandle(InMemoryFailedDocumentStreamObjectStore delegate)
        implements FailedDocumentStreamObjectStore {

        @Override
        public List<String> listKeys(String prefix) {
            return delegate.listKeys(prefix);
        }

        @Override
        public java.io.InputStream open(String key) throws java.io.IOException {
            return delegate.open(key);
        }

        @Override
        public java.util.Optional<byte[]> read(String key) {
            return delegate.read(key);
        }

        @Override
        public boolean putIfAbsent(String key, byte[] body) {
            return delegate.putIfAbsent(key, body);
        }

        @Override
        public void close() {
            // The test owns the store's lifetime.
        }
    }
}
