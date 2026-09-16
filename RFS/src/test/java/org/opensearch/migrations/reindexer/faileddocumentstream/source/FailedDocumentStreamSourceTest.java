package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailedDocumentStreamSourceTest {

    private static final String PREFIX = "rfs-failed-document-stream/";
    private static final String SESSION = "session-1";
    private static final String ORDERS = "orders-2024";

    private InMemoryFailedDocumentStreamObjectStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryFailedDocumentStreamObjectStore();
    }

    private FailedDocumentStreamSource sourceOver(List<String> allowlist, Set<FailureClass> classes)
        throws IOException {
        var manifest = new SessionSealer(store).seal(PREFIX, SESSION).manifest();
        return new FailedDocumentStreamSource(store, manifest, allowlist, classes, false);
    }

    private FailedDocumentStreamSource source() throws IOException {
        return sourceOver(List.of(), Set.of());
    }

    private static List<PositionedDocument> readAll(
        FailedDocumentStreamSource source, Partition partition, String cursor
    ) {
        var read = source.readDocuments(partition, cursor).collectList().block();
        return read == null ? List.of() : read;
    }

    private Partition onlyPartition(FailedDocumentStreamSource source, String collection) {
        var partitions = source.listPartitions(collection);
        assertThat(partitions, hasSize(1));
        return partitions.get(0);
    }

    @Test
    void emitsTheWholeRecord_notTheDocumentInsideIt() throws Exception {
        var line = FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE);
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1, List.of(line));

        try (var source = source()) {
            var documents = readAll(source, onlyPartition(source, ORDERS), null);

            assertThat(documents, hasSize(1));
            var document = documents.get(0).document();
            // Whole, so both transforms can see every field.
            var emitted = ObjectMapperFactory.createDefaultMapper()
                .readTree(new String(document.source(), StandardCharsets.UTF_8));
            assertThat(emitted.get("documentId").asText(), equalTo("doc-1"));
            assertThat(emitted.has("requestItem"), is(true));
            assertThat(emitted.has("responseItem"), is(true));
            // Not an operation until transform 1 has run.
            assertThat(document.id(), is(nullValue()));
        }
    }

    @Test
    void namesWhereEachDocumentCameFrom() throws Exception {
        var key = FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE),
                FailedDocumentStreamFixtures.record(ORDERS, "doc-2", FailureClass.NON_RETRYABLE)));

        try (var source = source()) {
            var documents = readAll(source, onlyPartition(source, ORDERS), null);

            assertThat(documents.get(1).document().sourceMetadata()
                .get(FailedDocumentStreamSource.META_OBJECT_KEY), equalTo(key));
            assertThat(documents.get(1).document().sourceMetadata()
                .get(FailedDocumentStreamSource.META_RECORD_ORDINAL), equalTo(1L));
        }
    }

    @Test
    void readsAPartitionAcrossEveryObjectTheManifestNames() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE)));
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 2,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-2", FailureClass.NON_RETRYABLE)));

        try (var source = source()) {
            assertThat(readAll(source, onlyPartition(source, ORDERS), null), hasSize(2));
        }
    }

    @Test
    void keepsDuplicates() throws Exception {
        // Redrive writes are id-addressed upserts; collapsing would break what a cursor means.
        var duplicate = FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE);
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(duplicate, duplicate));

        try (var source = source()) {
            assertThat(readAll(source, onlyPartition(source, ORDERS), null), hasSize(2));
        }
    }

    @Test
    void filtersByFailureClass() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE),
                FailedDocumentStreamFixtures.record(ORDERS, "doc-2", FailureClass.RETRYABLE_EXHAUSTED),
                FailedDocumentStreamFixtures.record(ORDERS, "doc-3", FailureClass.RETRYABLE_EXHAUSTED)));

        try (var source = sourceOver(List.of(), EnumSet.of(FailureClass.RETRYABLE_EXHAUSTED))) {
            var documents = readAll(source, onlyPartition(source, ORDERS), null);

            assertThat(documents, hasSize(2));
        }
    }

    @Test
    void aFilteredReadKeepsTheSameCursorsAsAnUnfilteredOne() throws Exception {
        // Ordinals count the raw stream, so filtering never moves a position.
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE),
                FailedDocumentStreamFixtures.record(ORDERS, "doc-2", FailureClass.RETRYABLE_EXHAUSTED)));

        String unfilteredSecondCursor;
        try (var source = source()) {
            unfilteredSecondCursor = readAll(source, onlyPartition(source, ORDERS), null).get(1).cursorAfter();
        }
        try (var source = sourceOver(List.of(), EnumSet.of(FailureClass.RETRYABLE_EXHAUSTED))) {
            var filtered = readAll(source, onlyPartition(source, ORDERS), null);

            assertThat(filtered, hasSize(1));
            assertThat(filtered.get(0).cursorAfter(), equalTo(unfilteredSecondCursor));
        }
    }

    @Test
    void filtersCollectionsByAllowlist() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE)));
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, "users", "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record("users", "user-1", FailureClass.NON_RETRYABLE)));

        try (var source = sourceOver(List.of(ORDERS), Set.of())) {
            assertThat(source.listCollections(), contains(ORDERS));
            assertThat(source.listPartitions("users"), is(empty()));
            assertThat(source.findPartition("users", "worker-a").isPresent(), is(false));
        }
    }

    @Test
    void failsLoudlyOnARecordWithNothingToReplay() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE),
                FailedDocumentStreamFixtures.recordWithoutRequestItem(ORDERS, "doc-2")));

        try (var source = source()) {
            var partition = onlyPartition(source, ORDERS);

            var thrown = assertThrows(FailedDocumentStreamSource.UnreadableRecordException.class,
                () -> readAll(source, partition, null));

            // Say which record it was.
            assertThat(thrown.getMessage(), containsString("#1"));
            assertThat(thrown.getMessage(), containsString("worker-a"));
            assertThat(thrown.getMessage(), containsString("requestItem"));
        }
    }

    @Test
    void failsLoudlyOnAnUnparseableRecord() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of("{\"this\": is not json"));

        try (var source = source()) {
            var partition = onlyPartition(source, ORDERS);

            assertThrows(FailedDocumentStreamSource.UnreadableRecordException.class,
                () -> readAll(source, partition, null));
        }
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        // A newer writer may add fields.
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var withExtra = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
            FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE));
        withExtra.put("somethingAddedLater", "value");
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(mapper.writeValueAsString(withExtra)));

        try (var source = source()) {
            assertThat(readAll(source, onlyPartition(source, ORDERS), null), hasSize(1));
        }
    }

    @Test
    void rejectsACursorFromAnotherPartition() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE)));

        try (var source = source()) {
            var partition = onlyPartition(source, ORDERS);
            var foreign = new FailedDocumentStreamCursor("some/other/worker/file.ndjson.gz", 0).encode();

            var thrown = assertThrows(IllegalArgumentException.class,
                () -> readAll(source, partition, foreign));

            assertThat(thrown.getMessage(), containsString("different session or partition"));
        }
    }

    @Test
    void readCollectionMetadataIsNotSupported() throws Exception {
        FailedDocumentStreamFixtures.putRotation(store, PREFIX, SESSION, ORDERS, "worker-a", 1,
            List.of(FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE)));

        try (var source = source()) {
            assertThrows(UnsupportedOperationException.class, () -> source.readCollectionMetadata(ORDERS));
        }
    }
}
