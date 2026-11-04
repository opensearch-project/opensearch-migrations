package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.faileddocumentstream.StagedRequestFailure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Opt-in slow path for plain index operations too large for one HTTP request. Assembly happens in
 * an unindexed staging document; a local reindex publishes the complete source to the target.
 * Chunk retries are idempotent. A durable, conditionally updated journal fences abandoned uploads
 * and tracks asynchronous publication across checkpoint replay. An unknown task submission stops
 * recovery instead of risking a second publication racing subsequent operations.
 */
@Slf4j
final class StagedDocumentWriter {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final String MARKER = "rfs_large_document_staging";
    private static final int PROTOCOL_VERSION = 2;
    private static final String INDEX_NAME = "_index";
    private static final String INDEX = "index";
    private static final String PHASE = "phase";
    private static final String PUBLISHING = "publishing";
    private static final String UPLOAD_ID = "uploadId";
    private static final String TASK_ID = "taskId";
    private static final String ASSEMBLING = "assembling";
    private static final String OWNER = "owner";
    private static final String STACK = "stack";
    private static final String SOURCE = "_source";
    private static final String PRIMARY_TERM = "_primary_term";
    private static final String SEQUENCE_NUMBER = "_seq_no";
    private static final String NO_PIPELINE = "_none";
    private static final String REQUEST_DURABILITY = "request";
    private static final String ENABLED = "enabled";
    private static final String ERROR = "error";
    private static final String CREATED = "created";
    private static final String UPDATED = "updated";
    private static final String PIPELINE = "pipeline";
    private static final String ROUTING = "routing";
    private static final Set<String> SUPPORTED_METADATA = Set.of(INDEX_NAME, "_id", ROUTING, PIPELINE, "require_alias");
    private static final String PUBLISH_SCRIPT =
        "if (!ctx._source.stack.isEmpty() || ctx._source.next != params.chunks"
            + " || !(ctx._source.document instanceof Map)) {"
            + " throw new IllegalStateException('Incomplete staged document'); }"
            + " def document = ctx._source.document;"
            + " for (def path : ctx._source.numbers) {"
            + " def parent = document;"
            + " for (int i = 0; i < path.size() - 1; i++) { parent = parent[path[i]]; }"
            + " def key = path[path.size() - 1]; String value = parent[key];"
            + " if (value.contains('.') || value.contains('e') || value.contains('E')) {"
            + " def decimal = new BigDecimal(value);"
            + " if (decimal.signum() == 0 && value.startsWith('-')) { parent[key] = -0.0; }"
            + " else { parent[key] = decimal; }"
            + " } else { parent[key] = new BigInteger(value); }"
            + " }"
            + " ctx._source = document; ctx._id = params.id;";
    private static final Retry IDEMPOTENT_RETRY = Retry.backoff(4, Duration.ofMillis(200))
        .maxBackoff(Duration.ofSeconds(2))
        .filter(error -> !(error instanceof IllegalArgumentException)
            && (!(error instanceof OpenSearchClient.OperationFailed failure)
                || failure.response.statusCode == 429 || failure.response.statusCode >= 500))
        .onRetryExhaustedThrow((spec, signal) -> signal.failure());

    private final RestClient transport;
    private final OperationSplitter splitter;
    private final String stagingIndex;
    private final long limit;
    private final Mono<Void> ready;
    private final Map<String, Scope> defaultScopes = new ConcurrentHashMap<>();
    private final AtomicReference<Scope> partitionScope = new AtomicReference<>();

    StagedDocumentWriter(RestClient transport, String stagingIndex, long limit) {
        this(transport, stagingIndex, limit, JsonDocumentChunks::split);
    }

    StagedDocumentWriter(RestClient transport, String stagingIndex, long limit, OperationSplitter splitter) {
        if (stagingIndex == null || !stagingIndex.matches("[a-z0-9.][a-z0-9._-]{0,254}")
            || stagingIndex.equals(".") || stagingIndex.equals("..")) {
            throw new IllegalArgumentException("The staging index must be a concrete lowercase index name");
        }
        this.transport = transport;
        this.splitter = splitter;
        this.stagingIndex = stagingIndex;
        this.limit = limit;
        this.ready = Mono.defer(this::prepareStagingIndex).cache();
    }

    Mono<Void> preparePartition(String collection, String partition) {
        return Mono.defer(() -> {
            var scope = new Scope(List.of(collection, partition));
            partitionScope.set(scope);
            return scope.prepared;
        });
    }

    /** Also gates ordinary requests: recovery must finish before replay can write a smaller document. */
    Mono<Void> beforeWrites(String defaultIndex) {
        return Mono.defer(() -> scope(defaultIndex).prepared);
    }

    private Scope scope(String defaultIndex) {
        var selected = partitionScope.get();
        return selected != null ? selected
            : defaultScopes.computeIfAbsent(defaultIndex, index -> new Scope(List.of(index, "direct-client")));
    }

    private final class Scope {
        final String journalId;
        final String owner = UUID.randomUUID().toString();
        final Mono<Void> prepared;

        Scope(List<String> identity) {
            journalId = "journal-" + digest(json(identity));
            prepared = ready.then(Mono.defer(() -> recover(this))).cache();
        }
    }

    Mono<OpenSearchClient.BulkResponse> write(
        String defaultIndex, BulkNdjson.EncodedOperation operation, IRfsContexts.IRequestContext context
    ) {
        return Mono.defer(() -> {
            var metadata = validateOperation(defaultIndex, operation);
            var attempt = new Attempt(scope(defaultIndex), context);
            attempt.uploadId = "upload-" + UUID.randomUUID();
            attempt.operation = MAPPER.createObjectNode().set(INDEX, metadata);
            attempt.documentDigest = digest(operation.body());
            return attempt.scope.prepared.then(claimUpload(attempt))
                .flatMap(journal -> createUpload(attempt)
                    .thenMany(splitter.split(operation, limit)
                        .onErrorMap(error -> {
                            attempt.chunkSequence = attempt.chunksCompleted;
                            return failure(attempt, "ENCODE_CHUNK", null,
                                stagingIndex + "/_update/" + attempt.uploadId, null, error);
                        })
                        // Zero prefetch: do not generate the next part until this one's ACK (including retries).
                        .concatMap(part -> {
                            attempt.chunkSequence = part.sequence();
                            return request(attempt, "CHUNK", HttpMethod.POST,
                                stagingIndex + "/_update/" + attempt.uploadId, part.body(), true)
                                .doOnSuccess(response -> attempt.chunksCompleted++);
                        }, 0))
                    .then(request(attempt, "REFRESH", HttpMethod.POST, stagingIndex + "/_refresh", null, true))
                    .then(Mono.defer(() -> publish(attempt, journal, metadata, attempt.chunksCompleted))))
                .flatMap(response -> cleanupUpload(attempt).thenReturn(response))
                .map(response -> new OpenSearchClient.BulkResponse(200, "OK", response.headers,
                    "{\"errors\":false,\"items\":[]}",
                    new MigrationMetrics.Operations(1, 0, operation.body().length),
                    new MigrationMetrics.Requests(1, attempt.totals.attempts, attempt.totals.retries, attempt.totals.bytes,
                        Duration.ofNanos(attempt.totals.nanos).toMillis())))
                .doOnError(error -> log.atError().setCause(error)
                    .setMessage("Large document was not acknowledged; retain staging document {}/{} and journal {} for recovery")
                    .addArgument(stagingIndex).addArgument(attempt.uploadId).addArgument(attempt.scope.journalId).log())
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        log.atWarn().setMessage("Large document upload cancelled; retain staging document {}/{} for recovery")
                            .addArgument(stagingIndex).addArgument(attempt.uploadId).log();
                    }
                });
        });
    }

    private Mono<Journal> claimUpload(Attempt attempt) {
        return readJournal(attempt).flatMap(previous -> {
            if (PUBLISHING.equals(previous.source.path(PHASE).asText())) {
                attempt.uploadId = previous.source.path(UPLOAD_ID).asText(null);
                attempt.taskId = previous.source.path(TASK_ID).asText(null);
                attempt.publicationMayHaveCommitted = true;
                return Mono.error(unknownPublication(attempt,
                    "A previous publication must be reconciled before another upload can start"));
            }
            var next = MAPPER.createObjectNode().put("protocol", PROTOCOL_VERSION)
                .put(PHASE, ASSEMBLING).put(UPLOAD_ID, attempt.uploadId).put(OWNER, attempt.scope.owner)
                .put("documentSha256", attempt.documentDigest);
            next.set("operation", attempt.operation);
            return writeJournal(attempt, previous, next, "CLAIM_UPLOAD");
        });
    }

    private Mono<Void> createUpload(Attempt attempt) {
        var initial = json(Map.of(STACK, List.of(), "numbers", List.of(), "next", 0));
        // Create-only: a delayed retry of an initial PUT must never reset chunks already applied.
        return request(attempt, "CREATE", HttpMethod.PUT, stagingIndex + "/_create/" + attempt.uploadId, initial, true)
            .onErrorResume(error -> hasStatus(error, 409)
                ? request(attempt, "VERIFY_CREATE", HttpMethod.GET,
                    documentPath(attempt.uploadId) + "?_source_includes=next,stack", null, true)
                    .map(response -> {
                        var source = parse(response.body).path(SOURCE);
                        if (source.path("next").asLong(-1) != 0 || !source.path(STACK).isArray()
                            || !source.path(STACK).isEmpty()) {
                            throw new IllegalStateException("An upload ID was reused");
                        }
                        return response;
                    })
                : Mono.error(error))
            .then();
    }

    private Mono<Void> recover(Scope scope) {
        var attempt = new Attempt(scope, null);
        return readJournal(attempt).flatMap(journal -> {
            attempt.uploadId = journal.source.path(UPLOAD_ID).asText(null);
            attempt.taskId = journal.source.path(TASK_ID).asText(null);
            switch (journal.source.path(PHASE).asText()) {
                case ASSEMBLING:
                    // Fence a predecessor before ANY replayed target write, even a small ordinary bulk.
                    return writeJournal(attempt, journal, journal.source.deepCopy().put(PHASE, "abandoned")
                        .put(OWNER, scope.owner), "FENCE_UPLOAD").then();
                case PUBLISHING:
                    attempt.publicationMayHaveCommitted = true;
                    if (attempt.taskId == null) {
                        return Mono.error(unknownPublication(attempt,
                            "Submission has no durable task ID. Reconcile the journal and cluster tasks before resuming; "
                                + "do not delete the journal or retry publication blindly."));
                    }
                    // Changing the owner also fences a predecessor's delayed completion/ACK recovery.
                    return writeJournal(attempt, journal, journal.source.deepCopy().put(OWNER, scope.owner), "CLAIM_RECOVERY")
                        .flatMap(owned -> finishPublication(attempt, owned, false)).then();
                default:
                    return Mono.empty();
            }
        });
    }

    private Mono<Journal> readJournal(Attempt attempt) {
        return request(attempt, "READ_JOURNAL", HttpMethod.GET, journalPath(attempt), null, true)
            .map(response -> {
                var result = parse(response.body);
                var source = result.path(SOURCE);
                if (!source.isObject() || source.path("protocol").asInt() != PROTOCOL_VERSION
                    || !Set.of(ASSEMBLING, "abandoned", PUBLISHING, "complete", "failed").contains(source.path(PHASE).asText())
                    || !result.path(SEQUENCE_NUMBER).canConvertToLong() || !result.path(PRIMARY_TERM).canConvertToLong()) {
                    throw new IllegalArgumentException("Invalid large document recovery journal");
                }
                return new Journal((ObjectNode) source, result.path(SEQUENCE_NUMBER).asLong(), result.path(PRIMARY_TERM).asLong());
            })
            .onErrorResume(error -> hasStatus(error, 404)
                ? Mono.just(new Journal(MAPPER.createObjectNode(), -1, -1)) : Mono.error(error));
    }

    private Mono<Journal> writeJournal(Attempt attempt, Journal previous, ObjectNode next, String stage) {
        String path = journalPath(attempt) + (previous.sequence < 0 ? "?op_type=create"
            : "?if_seq_no=" + previous.sequence + "&if_primary_term=" + previous.primaryTerm);
        return request(attempt, stage, HttpMethod.PUT, path, json(next), false)
            .map(response -> {
                var result = parse(response.body);
                if (!result.path(SEQUENCE_NUMBER).canConvertToLong() || !result.path(PRIMARY_TERM).canConvertToLong()) {
                    throw new IllegalStateException("Journal write did not return sequence metadata");
                }
                return new Journal(next, result.path(SEQUENCE_NUMBER).asLong(), result.path(PRIMARY_TERM).asLong());
            })
            // A CAS is not blindly retryable. Read back a lost ACK and require our exact generation/state.
            .onErrorResume(error -> readJournal(attempt)
                .flatMap(observed -> next.equals(observed.source) ? Mono.just(observed) : Mono.error(error)));
    }

    private String journalPath(Attempt attempt) {
        return documentPath(attempt.scope.journalId);
    }

    private String documentPath(String id) {
        return stagingIndex + "/_doc/" + id;
    }

    private Mono<Void> cleanupUpload(Attempt attempt) {
        return request(attempt, "CLEANUP", HttpMethod.DELETE, documentPath(attempt.uploadId), null, true)
            .onErrorResume(error -> {
                log.atWarn().setCause(error).setMessage("Publication finished; could not remove staging document {}/{}")
                    .addArgument(stagingIndex).addArgument(attempt.uploadId).log();
                return Mono.empty();
            }).then();
    }

    private JsonNode validateOperation(String defaultIndex, BulkNdjson.EncodedOperation operation) {
        if (!operation.upsert() || operation.body() == null) {
            throw unsupported(operation, "Staging requires an index operation with a document body.");
        }
        return validateMetadata(defaultIndex, parse(operation.action()).path(INDEX), operation);
    }

    private JsonNode validateMetadata(String defaultIndex, JsonNode meta, BulkNdjson.EncodedOperation operation) {
        if (!(meta instanceof ObjectNode metadata) || !(metadata.get("_id") instanceof TextNode id) || id.textValue().isEmpty()) {
            throw unsupported(operation, "Staging requires an index operation with an explicit document ID.");
        }
        metadata.fieldNames().forEachRemaining(name -> {
            if (!SUPPORTED_METADATA.contains(name)) {
                throw unsupported(operation, "Staging does not support action metadata '" + name
                    + "'. Version conditions, create operations, and document types cannot be silently changed.");
            }
        });
        // This tree was parsed from the retained action bytes for this attempt; no copy is needed.
        if (!metadata.hasNonNull(INDEX_NAME)) {
            metadata.put(INDEX_NAME, defaultIndex);
        }
        if (stagingIndex.equals(metadata.path(INDEX_NAME).asText())) {
            throw unsupported(operation, "The staging index must differ from the destination.");
        }
        return metadata;
    }

    private OversizedDocumentException unsupported(BulkNdjson.EncodedOperation operation, String message) {
        return new OversizedDocumentException(operation.size(), limit, message);
    }

    private Mono<Void> prepareStagingIndex() {
        var body = json(Map.of(
            "settings", Map.of("number_of_shards", 1, "number_of_replicas", 1,
                "index.default_pipeline", NO_PIPELINE, "index.final_pipeline", NO_PIPELINE, "index.translog.durability", REQUEST_DURABILITY),
            "mappings", Map.of(ENABLED, false, "_meta", Map.of(MARKER, PROTOCOL_VERSION),
                SOURCE, Map.of(ENABLED, true, "includes", List.of(), "excludes", List.of()))));
        // Setup is shared by concurrent writers and has its own lifetime and request context.
        return send(HttpMethod.PUT, stagingIndex, body, null, null, true)
            .onErrorResume(OpenSearchClient.OperationFailed.class, error ->
                error.response.statusCode == 400
                    && parse(error.response.body).path(ERROR).path("type").asText().equals("resource_already_exists_exception")
                    ? Mono.empty() : Mono.error(error))
            .then(send(HttpMethod.GET, stagingIndex, null, null, null, true))
            .flatMap(response -> {
                var index = parse(response.body).path(stagingIndex);
                var mappings = index.path("mappings");
                var source = mappings.path(SOURCE);
                var settings = index.path("settings").path(INDEX);
                if (!mappings.path(ENABLED).isBoolean() || mappings.path(ENABLED).asBoolean()
                    || mappings.path("_meta").path(MARKER).asInt() != PROTOCOL_VERSION
                    || (source.has(ENABLED) && !source.path(ENABLED).asBoolean())
                    || source.path("includes").size() != 0 || source.path("excludes").size() != 0
                    || !NO_PIPELINE.equals(settings.path("default_pipeline").asText(NO_PIPELINE))
                    || !NO_PIPELINE.equals(settings.path("final_pipeline").asText(NO_PIPELINE))
                    || !REQUEST_DURABILITY.equalsIgnoreCase(settings.path("translog").path("durability").asText(REQUEST_DURABILITY))
                    || mappings.path("_routing").path("required").asBoolean()) {
                    return Mono.error(new IllegalArgumentException(
                        "Staging requires an RFS staging index with mappings disabled, complete _source, and no ingest pipelines"));
                }
                return Mono.empty();
            });
    }

    private Mono<HttpResponse> publish(
        Attempt attempt, Journal journal, JsonNode metadata, long chunks
    ) {
        var destination = new HashMap<String, Object>();
        destination.put(INDEX, metadata.path(INDEX_NAME).asText());
        if (metadata.hasNonNull(ROUTING)) {
            destination.put(ROUTING, "=" + metadata.path(ROUTING).asText());
        } else {
            destination.put(ROUTING, "discard");
        }
        if (metadata.hasNonNull(PIPELINE)) {
            destination.put(PIPELINE, metadata.path(PIPELINE).asText());
        }
        byte[] body = json(Map.of(
            "source", Map.of(INDEX, stagingIndex, "query", Map.of("ids", Map.of("values", List.of(attempt.uploadId)))),
            "dest", destination,
            "max_docs", 1,
            "script", Map.of("lang", "painless", "source", PUBLISH_SCRIPT,
                "params", Map.of("id", metadata.path("_id").asText(), "chunks", chunks))));
        if (body.length > limit) {
            return Mono.error(new OversizedDocumentException(body.length, limit, "Publication metadata does not fit."));
        }
        String path = "_reindex?wait_for_completion=false"
            + (metadata.path("require_alias").asBoolean() ? "&require_alias=true" : "");
        attempt.publicationPath = path;
        attempt.publicationBody = body;
        return writeJournal(attempt, journal, journal.source.deepCopy().put(PHASE, PUBLISHING), "CLAIM_PUBLICATION")
            .flatMap(publishing -> {
                attempt.publicationMayHaveCommitted = true;
                // Disable BOTH application and transport retries for task submission.
                return request(attempt, "SUBMIT_PUBLICATION", HttpMethod.POST, path, body, false)
                    .flatMap(response -> {
                        String taskId = parse(response.body).path("task").asText();
                        if (!taskId.matches("[A-Za-z0-9_-]+:[0-9]+")) {
                            return Mono.error(unknownPublication(attempt, "Publication did not return a valid task ID"));
                        }
                        attempt.taskId = taskId;
                        return writeJournal(attempt, publishing, publishing.source.deepCopy().put(TASK_ID, taskId), "RECORD_TASK");
                    })
                    .flatMap(recorded -> finishPublication(attempt, recorded, true));
            });
    }

    private Mono<HttpResponse> finishPublication(Attempt attempt, Journal journal, boolean reportFailure) {
        // A timed wait can return an HTTP timeout error while the task is healthy. Poll status instead.
        String path = "_tasks/" + attempt.taskId;
        return Mono.defer(() -> request(attempt, "WAIT_PUBLICATION", HttpMethod.GET, path, null, true))
            .repeatWhen(repeat -> repeat.delayElements(Duration.ofMillis(200)))
            .filter(response -> isTaskComplete(attempt, path, response))
            .next()
            .flatMap(response -> recordPublicationResult(attempt, journal, response, reportFailure));
    }

    private boolean isTaskComplete(Attempt attempt, String path, HttpResponse response) {
        var result = parse(response.body);
        if (!result.path("completed").isBoolean()) {
            throw failure(attempt, "WAIT_PUBLICATION", HttpMethod.GET, path, null,
                new OpenSearchClient.OperationFailed("Invalid task status response", response));
        }
        return result.path("completed").asBoolean();
    }

    private Mono<HttpResponse> recordPublicationResult(
        Attempt attempt, Journal journal, HttpResponse response, boolean reportFailure
    ) {
        var task = parse(response.body);
        var result = task.path("response");
        boolean success = successfulPublication(task);
        // A terminal task cannot publish later, including when it returned a mapping error.
        attempt.publicationMayHaveCommitted = success || task.hasNonNull(ERROR)
            || result.path(CREATED).asLong() > 0 || result.path(UPDATED).asLong() > 0;
        return writeJournal(attempt, journal, journal.source.deepCopy()
            .put(PHASE, success ? "complete" : "failed"), "COMPLETE_PUBLICATION")
            .flatMap(completed -> {
                if (!success && reportFailure) {
                    return Mono.error(failure(attempt, "PUBLICATION_RESULT", HttpMethod.POST,
                        attempt.publicationPath == null ? "_reindex" : attempt.publicationPath, attempt.publicationBody,
                        new OpenSearchClient.OperationFailed("Staged document publication did not index exactly one document", response)));
                }
                return !reportFailure && success ? cleanupUpload(attempt).thenReturn(response) : Mono.just(response);
            });
    }

    static boolean successfulPublication(JsonNode task) {
        var result = task.path("response");
        if (task.hasNonNull(ERROR) || !result.path("timed_out").isBoolean() || result.path("timed_out").asBoolean()
            || !result.path("failures").isArray() || !result.path("failures").isEmpty()) {
            return false;
        }
        for (String counter : List.of("total", CREATED, UPDATED, "deleted", "version_conflicts", "noops")) {
            var value = result.path(counter);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
                return false;
            }
        }
        return result.path("total").asLong() == 1
            && ((result.path(CREATED).asLong() == 1 && result.path(UPDATED).asLong() == 0)
                || (result.path(CREATED).asLong() == 0 && result.path(UPDATED).asLong() == 1))
            && result.path("deleted").asLong() == 0 && result.path("version_conflicts").asLong() == 0
            && result.path("noops").asLong() == 0;
    }

    private Mono<HttpResponse> request(
        Attempt attempt, String stage, HttpMethod method, String path, byte[] body, boolean retryable
    ) {
        var headers = stage.equals("SUBMIT_PUBLICATION") ? Map.of("X-Opaque-Id", List.of(attempt.uploadId)) : null;
        return send(method, path, body, attempt.context, attempt.totals, retryable, headers)
            .onErrorMap(error -> failure(attempt, stage, method, path, body, error));
    }

    private StagedDocumentException unknownPublication(Attempt attempt, String message) {
        return failure(attempt, "RECOVERY", HttpMethod.GET, journalPath(attempt), null, new IllegalStateException(message));
    }

    private StagedDocumentException failure(
        Attempt attempt, String stage, HttpMethod method, String path, byte[] body, Throwable error
    ) {
        if (error instanceof StagedDocumentException staged) {
            return staged;
        }
        var response = responseOf(error);
        return new StagedDocumentException(new StagedRequestFailure(stagingIndex, attempt.scope.journalId,
            attempt.uploadId, stage, stage.equals("CHUNK") || stage.equals("ENCODE_CHUNK") ? attempt.chunkSequence : null, attempt.taskId,
            method == null ? null : method.name(), path, body == null ? null : new String(body, StandardCharsets.UTF_8),
            response == null ? null : response.statusCode, response == null ? null : response.body,
            error.getMessage(), attempt.publicationMayHaveCommitted), error);
    }

    static HttpResponse responseOf(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof OpenSearchClient.OperationFailed failed) {
                return failed.response;
            }
        }
        return null;
    }

    private static boolean hasStatus(Throwable error, int status) {
        var response = responseOf(error);
        return response != null && response.statusCode == status;
    }

    private Mono<HttpResponse> send(
        HttpMethod method, String path, byte[] body, IRfsContexts.IRequestContext context, Totals totals, boolean retryable
    ) {
        return send(method, path, body, context, totals, retryable, null);
    }

    private Mono<HttpResponse> send(
        HttpMethod method, String path, byte[] body, IRfsContexts.IRequestContext context, Totals totals, boolean retryable,
        Map<String, List<String>> headers
    ) {
        return Mono.defer(() -> {
            if (body != null && body.length > limit) {
                return Mono.error(new OversizedDocumentException(body.length, limit,
                    "The staging protocol envelope or publication metadata does not fit."));
            }
            var attempts = new long[1];
            var request = Mono.defer(() -> {
                if (totals != null) {
                    totals.recordAttempt(body, attempts[0]);
                }
                attempts[0]++;
                return sendOnce(method, path, body, context, totals, headers);
            });
            return retryable ? request.retryWhen(IDEMPOTENT_RETRY) : request;
        });
    }

    private Mono<HttpResponse> sendOnce(
        HttpMethod method, String path, byte[] body, IRfsContexts.IRequestContext context, Totals totals,
        Map<String, List<String>> headers
    ) {
        long start = System.nanoTime();
        // Staging requests are uncompressed, so the measured bytes are also the HTTP body bytes.
        return transport.asyncRequestBytesWithoutRetry(method, path, body, headers, context)
            .doOnSuccess(response -> recordDuration(totals, start))
            .doOnError(error -> recordDuration(totals, start))
            .map(response -> {
                if (response.statusCode < 200 || response.statusCode >= 300) {
                    throw new OpenSearchClient.OperationFailed("Staged document request failed: " + method + " " + path, response);
                }
                return response;
            });
    }

    private static void recordDuration(Totals totals, long start) {
        if (totals != null) {
            totals.nanos += System.nanoTime() - start;
        }
    }

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode parse(byte[] value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record Journal(ObjectNode source, long sequence, long primaryTerm) {}

    private static final class Attempt {
        final Scope scope;
        final IRfsContexts.IRequestContext context;
        final Totals totals = new Totals();
        String uploadId;
        Long chunkSequence;
        long chunksCompleted;
        String taskId;
        boolean publicationMayHaveCommitted;
        String publicationPath;
        byte[] publicationBody;
        JsonNode operation;
        String documentDigest;

        Attempt(Scope scope, IRfsContexts.IRequestContext context) {
            this.scope = scope;
            this.context = context;
        }
    }

    private static final class Totals {
        long attempts;
        long retries;
        long bytes;
        long nanos;

        void recordAttempt(byte[] body, long previousAttempts) {
            attempts++;
            bytes += body == null ? 0 : body.length;
            if (previousAttempts > 0) {
                retries++;
            }
        }
    }
}
