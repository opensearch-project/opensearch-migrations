package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Reads a sealed failure-stream session as a document source, so a redrive is a migration from a
 * different source rather than a feature of its own.
 *
 * <p>Collections are {@code index=} prefixes, partitions are {@code worker=} prefixes, documents
 * are NDJSON records.
 *
 * <p>A record is emitted whole, not the document inside it.
 * {@link FailedDocumentStreamRecordTransformer} turns it back into a bulk operation and is
 * mandatory for this source.
 *
 * <p>Duplicates are kept: redrive writes are id-addressed upserts, and collapsing them would break
 * what a cursor means.
 */
@Slf4j
public class FailedDocumentStreamSource implements DocumentSource {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    /** The bulk operation as it was sent. Without it there is nothing to redrive. */
    static final String FIELD_REQUEST_ITEM = "requestItem";
    static final String FIELD_FAILURE_CLASS = "failureClass";

    /** Diagnostics, so a later failure can name where the record came from. */
    public static final String META_OBJECT_KEY = "fds.objectKey";
    public static final String META_RECORD_ORDINAL = "fds.recordOrdinal";

    private final FailedDocumentStreamObjectStore store;
    private final SessionManifest manifest;
    private final Set<String> collectionAllowlist;
    private final Set<FailureClass> failureClasses;
    private final boolean ownsStore;

    /**
     * @param collectionAllowlist target indices to read; empty means all
     * @param failureClasses      failure classes to read; empty means all
     * @param ownsStore           whether closing this source should close the store
     */
    public FailedDocumentStreamSource(
        FailedDocumentStreamObjectStore store,
        SessionManifest manifest,
        List<String> collectionAllowlist,
        Set<FailureClass> failureClasses,
        boolean ownsStore
    ) {
        this.store = store;
        this.manifest = manifest;
        this.collectionAllowlist = collectionAllowlist == null ? Set.of() : Set.copyOf(collectionAllowlist);
        this.failureClasses = failureClasses == null ? Set.of() : Set.copyOf(failureClasses);
        this.ownsStore = ownsStore;
    }

    @Override
    public List<String> listCollections() {
        return manifest.collectionNames().stream()
            .filter(name -> collectionAllowlist.isEmpty() || collectionAllowlist.contains(name))
            .toList();
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        if (!collectionAllowlist.isEmpty() && !collectionAllowlist.contains(collectionName)) {
            return List.of();
        }
        return manifest.collection(collectionName)
            .map(collection -> collection.partitions().stream()
                .map(partition -> (Partition) new FailedDocumentStreamPartition(
                    collectionName, partition.name(), partition.objectKeys()))
                .toList())
            .orElseGet(List::of);
    }

    @Override
    public Optional<Partition> findPartition(String collectionName, String partitionName) {
        if (!collectionAllowlist.isEmpty() && !collectionAllowlist.contains(collectionName)) {
            return Optional.empty();
        }
        return manifest.partition(collectionName, partitionName)
            .map(partition -> new FailedDocumentStreamPartition(
                collectionName, partition.name(), partition.objectKeys()));
    }

    @Override
    public Flux<PositionedDocument> readDocuments(Partition partition, String startingCursor) {
        if (!(partition instanceof FailedDocumentStreamPartition fdsPartition)) {
            return Flux.error(new IllegalArgumentException(
                "Expected a failure-stream partition, got " + partition.getClass().getName()));
        }
        var resumePoint = startingCursor == null ? null : FailedDocumentStreamCursor.decode(startingCursor);
        var keys = fdsPartition.objectKeys();
        if (resumePoint != null && !keys.contains(resumePoint.objectKey())) {
            return Flux.error(new IllegalArgumentException("Cursor names object '" + resumePoint.objectKey()
                + "', which is not part of partition '" + fdsPartition.name() + "' of collection '"
                + fdsPartition.collectionName() + "'. The cursor belongs to a different session or partition."));
        }
        // The named object resumes partway; everything after it is read whole.
        var remaining = resumePoint == null
            ? keys
            : keys.subList(keys.indexOf(resumePoint.objectKey()), keys.size());
        return Flux.fromIterable(remaining)
            .concatMap(key -> readObject(
                key, resumePoint != null && key.equals(resumePoint.objectKey()) ? resumePoint.ordinal() : -1L));
    }

    /** One object's records, skipping through {@code skipThroughOrdinal} (-1 reads it whole). */
    private Flux<PositionedDocument> readObject(String key, long skipThroughOrdinal) {
        return Flux.using(
                () -> openRecords(key),
                reader -> Flux.fromStream(reader.lines()),
                FailedDocumentStreamSource::closeQuietly)
            .index()
            .filter(indexed -> indexed.getT1() > skipThroughOrdinal)
            .handle((indexed, sink) -> {
                var ordinal = indexed.getT1();
                var line = indexed.getT2();
                if (line.isBlank()) {
                    return;
                }
                var document = toDocument(key, ordinal, line);
                document.ifPresent(doc -> sink.next(
                    new PositionedDocument(doc, new FailedDocumentStreamCursor(key, ordinal).encode())));
            });
    }

    /**
     * One record as a document, or empty when its failure class was filtered out.
     *
     * <p>Anything the sink could not have written fails the run naming the object and ordinal.
     * Unknown fields are ignored, so a newer writer stays readable.
     */
    private Optional<Document> toDocument(String key, long ordinal, String line) {
        com.fasterxml.jackson.databind.JsonNode record;
        try {
            record = MAPPER.readTree(line);
        } catch (IOException e) {
            throw new UnreadableRecordException(key, ordinal, "it is not valid JSON", e);
        }
        if (record == null || !record.isObject()) {
            throw new UnreadableRecordException(key, ordinal, "it is not a JSON object", null);
        }
        var requestItem = record.get(FIELD_REQUEST_ITEM);
        if (requestItem == null || requestItem.isNull() || !requestItem.isObject()) {
            throw new UnreadableRecordException(key, ordinal,
                "it has no '" + FIELD_REQUEST_ITEM + "' object to replay", null);
        }
        if (!failureClasses.isEmpty() && !matchesFailureClass(record)) {
            return Optional.empty();
        }
        return Optional.of(new Document(
            // Not an operation yet, so no id to carry. Transform 1 sets one.
            null,
            line.getBytes(StandardCharsets.UTF_8),
            Document.Operation.UPSERT,
            Map.of(),
            Map.of(META_OBJECT_KEY, key, META_RECORD_ORDINAL, ordinal)));
    }

    private boolean matchesFailureClass(com.fasterxml.jackson.databind.JsonNode record) {
        var raw = record.path(FIELD_FAILURE_CLASS).asText(null);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return failureClasses.contains(FailureClass.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private BufferedReader openRecords(String key) throws IOException {
        var raw = store.open(key);
        try {
            return new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
        } catch (IOException e) {
            closeQuietly(raw);
            throw new UnreadableRecordException(key, -1, "its gzip stream could not be opened", e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Error closing a failure-stream object reader").log();
        }
    }

    /** Not supported: the target index already exists, so there is nothing here to create. */
    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        throw new UnsupportedOperationException(
            "A failure stream carries no collection metadata; the target index '" + collectionName
                + "' already exists. Redrive runs on the coordinated path, which does not create collections.");
    }

    @Override
    public void close() throws Exception {
        if (ownsStore) {
            store.close();
        }
    }

    /** A record the sink could not have written. Fails the run rather than being skipped. */
    public static class UnreadableRecordException extends UncheckedIOException {
        public UnreadableRecordException(String objectKey, long ordinal, String why, Throwable cause) {
            super(new IOException("Failure-stream record " + (ordinal < 0 ? "" : "#" + ordinal + " ")
                + "in " + objectKey + " cannot be read: " + why
                + ". Every record the sink writes converts back to a document, so this is corruption"
                + " or a schema break, not a kind of failure to route around.", cause));
        }
    }
}
