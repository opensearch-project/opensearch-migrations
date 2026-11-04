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

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.faileddocumentstream.StagedRequestFailure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
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
    private static final Set<String> SUPPORTED_METADATA = Set.of("_index", "_id", "routing", "pipeline", "require_alias");
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
    private final String stagingIndex;
    private final long limit;
    private final Mono<Void> ready;
    private final Map<String, Scope> defaultScopes = new ConcurrentHashMap<>();
    private volatile Scope partitionScope;

    StagedDocumentWriter(RestClient transport, String stagingIndex, long limit) {
        if (stagingIndex == null || !stagingIndex.matches("[a-z0-9.][a-z0-9._-]{0,254}")
            || stagingIndex.equals(".") || stagingIndex.equals("..")) {
            throw new IllegalArgumentException("The staging index must be a concrete lowercase index name");
        }
        this.transport = transport;
        this.stagingIndex = stagingIndex;
        this.limit = limit;
        this.ready = Mono.defer(this::prepareStagingIndex).cache();
    }

    Mono<Void> preparePartition(String collection, String partition) {
        return Mono.defer(() -> {
            var scope = new Scope(List.of(collection, partition));
            partitionScope = scope;
            return scope.prepared;
        });
    }

    /** Also gates ordinary requests: recovery must finish before replay can write a smaller document. */
    Mono<Void> beforeWrites(String defaultIndex) {
        return Mono.defer(() -> scope(defaultIndex).prepared);
    }

    private Scope scope(String defaultIndex) {
        var selected = partitionScope;
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
            attempt.operation = MAPPER.createObjectNode().set("index", metadata);
            attempt.documentDigest = digest(operation.body());
            // Check script overhead before creating any staging state.
            final JsonDocumentChunks chunks;
            try {
                chunks = new JsonDocumentChunks(operation.body(), limit);
            } catch (IOException e) {
                return Mono.error(new UncheckedIOException(e));
            }
            return Mono.using(() -> chunks, resource ->
                attempt.scope.prepared.then(claimUpload(attempt))
                    .flatMap(journal -> createUpload(attempt)
                        .thenMany(Flux.fromIterable(() -> resource)
                            .onErrorMap(error -> {
                                attempt.chunkSequence = resource.chunksProduced();
                                return failure(attempt, "ENCODE_CHUNK", null,
                                    stagingIndex + "/_update/" + attempt.uploadId, null, error);
                            })
                            .concatMap(body -> {
                                attempt.chunkSequence = parse(body).path("script").path("params").path("seq").asLong();
                                return request(attempt, "CHUNK", HttpMethod.POST,
                                    stagingIndex + "/_update/" + attempt.uploadId, body, true);
                            }, 1))
                        .then(request(attempt, "REFRESH", HttpMethod.POST, stagingIndex + "/_refresh", null, true))
                        .then(Mono.defer(() -> publish(attempt, journal, metadata, resource.chunksProduced()))))
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
                    }),
                resource -> {
                    try {
                        resource.close();
                    } catch (IOException e) {
                        log.atWarn().setCause(e).setMessage("Could not close document chunk parser").log();
                    }
                });
        });
    }

    private Mono<Journal> claimUpload(Attempt attempt) {
        return readJournal(attempt).flatMap(previous -> {
            if ("publishing".equals(previous.source.path("phase").asText())) {
                attempt.uploadId = previous.source.path("uploadId").asText(null);
                attempt.taskId = previous.source.path("taskId").asText(null);
                attempt.publicationMayHaveCommitted = true;
                return Mono.error(unknownPublication(attempt,
                    "A previous publication must be reconciled before another upload can start"));
            }
            var next = MAPPER.createObjectNode().put("protocol", PROTOCOL_VERSION)
                .put("phase", "assembling").put("uploadId", attempt.uploadId).put("owner", attempt.scope.owner)
                .put("documentSha256", attempt.documentDigest);
            next.set("operation", attempt.operation);
            return writeJournal(attempt, previous, next, "CLAIM_UPLOAD");
        });
    }

    private Mono<Void> createUpload(Attempt attempt) {
        var initial = json(Map.of("stack", List.of(), "numbers", List.of(), "next", 0));
        // Create-only: a delayed retry of an initial PUT must never reset chunks already applied.
        return request(attempt, "CREATE", HttpMethod.PUT, stagingIndex + "/_create/" + attempt.uploadId, initial, true)
            .onErrorResume(error -> hasStatus(error, 409)
                ? request(attempt, "VERIFY_CREATE", HttpMethod.GET,
                    stagingIndex + "/_doc/" + attempt.uploadId + "?_source_includes=next,stack", null, true)
                    .map(response -> {
                        var source = parse(response.body).path("_source");
                        if (source.path("next").asLong(-1) != 0 || !source.path("stack").isArray()
                            || !source.path("stack").isEmpty()) {
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
            attempt.uploadId = journal.source.path("uploadId").asText(null);
            attempt.taskId = journal.source.path("taskId").asText(null);
            switch (journal.source.path("phase").asText()) {
                case "assembling":
                    // Fence a predecessor before ANY replayed target write, even a small ordinary bulk.
                    return writeJournal(attempt, journal, journal.source.deepCopy().put("phase", "abandoned")
                        .put("owner", scope.owner), "FENCE_UPLOAD").then();
                case "publishing":
                    attempt.publicationMayHaveCommitted = true;
                    if (attempt.taskId == null) {
                        return Mono.error(unknownPublication(attempt,
                            "Submission has no durable task ID. Reconcile the journal and cluster tasks before resuming; "
                                + "do not delete the journal or retry publication blindly."));
                    }
                    // Changing the owner also fences a predecessor's delayed completion/ACK recovery.
                    return writeJournal(attempt, journal, journal.source.deepCopy().put("owner", scope.owner), "CLAIM_RECOVERY")
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
                var source = result.path("_source");
                if (!source.isObject() || source.path("protocol").asInt() != PROTOCOL_VERSION
                    || !Set.of("assembling", "abandoned", "publishing", "complete", "failed").contains(source.path("phase").asText())
                    || !result.path("_seq_no").canConvertToLong() || !result.path("_primary_term").canConvertToLong()) {
                    throw new IllegalArgumentException("Invalid large document recovery journal");
                }
                return new Journal((ObjectNode) source, result.path("_seq_no").asLong(), result.path("_primary_term").asLong());
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
                if (!result.path("_seq_no").canConvertToLong() || !result.path("_primary_term").canConvertToLong()) {
                    throw new IllegalStateException("Journal write did not return sequence metadata");
                }
                return new Journal(next, result.path("_seq_no").asLong(), result.path("_primary_term").asLong());
            })
            // A CAS is not blindly retryable. Read back a lost ACK and require our exact generation/state.
            .onErrorResume(error -> readJournal(attempt)
                .flatMap(observed -> next.equals(observed.source) ? Mono.just(observed) : Mono.error(error)));
    }

    private String journalPath(Attempt attempt) {
        return stagingIndex + "/_doc/" + attempt.scope.journalId;
    }

    private Mono<Void> cleanupUpload(Attempt attempt) {
        return request(attempt, "CLEANUP", HttpMethod.DELETE, stagingIndex + "/_doc/" + attempt.uploadId, null, true)
            .onErrorResume(error -> {
                log.atWarn().setCause(error).setMessage("Publication finished; could not remove staging document {}/{}")
                    .addArgument(stagingIndex).addArgument(attempt.uploadId).log();
                return Mono.empty();
            }).then();
    }

    private JsonNode validateOperation(String defaultIndex, BulkNdjson.EncodedOperation operation) {
        var action = parse(operation.action());
        var meta = action.path("index");
        if (!operation.upsert() || !meta.isObject() || operation.body() == null
            || !meta.path("_id").isTextual() || meta.path("_id").asText().isEmpty()) {
            throw unsupported(operation, "Staging requires an index operation with an explicit document ID.");
        }
        var names = meta.fieldNames();
        while (names.hasNext()) {
            var name = names.next();
            if (!SUPPORTED_METADATA.contains(name)) {
                throw unsupported(operation, "Staging does not support action metadata '" + name
                    + "'. Version conditions, create operations, and document types cannot be silently changed.");
            }
        }
        var result = meta.deepCopy();
        if (!result.hasNonNull("_index")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("_index", defaultIndex);
        }
        if (stagingIndex.equals(result.path("_index").asText())) {
            throw unsupported(operation, "The staging index must differ from the destination.");
        }
        return result;
    }

    private OversizedDocumentException unsupported(BulkNdjson.EncodedOperation operation, String message) {
        return new OversizedDocumentException(operation.size(), limit, message);
    }

    private Mono<Void> prepareStagingIndex() {
        var body = json(Map.of(
            "settings", Map.of("number_of_shards", 1, "number_of_replicas", 1,
                "index.default_pipeline", "_none", "index.final_pipeline", "_none", "index.translog.durability", "request"),
            "mappings", Map.of("enabled", false, "_meta", Map.of(MARKER, PROTOCOL_VERSION),
                "_source", Map.of("enabled", true, "includes", List.of(), "excludes", List.of()))));
        // Setup is shared by concurrent writers and has its own lifetime and request context.
        return send(HttpMethod.PUT, stagingIndex, body, null, null, true)
            .onErrorResume(OpenSearchClient.OperationFailed.class, error ->
                error.response.statusCode == 400
                    && parse(error.response.body).path("error").path("type").asText().equals("resource_already_exists_exception")
                    ? Mono.empty() : Mono.error(error))
            .then(send(HttpMethod.GET, stagingIndex, null, null, null, true))
            .flatMap(response -> {
                var index = parse(response.body).path(stagingIndex);
                var mappings = index.path("mappings");
                var source = mappings.path("_source");
                var settings = index.path("settings").path("index");
                if (!mappings.path("enabled").isBoolean() || mappings.path("enabled").asBoolean()
                    || mappings.path("_meta").path(MARKER).asInt() != PROTOCOL_VERSION
                    || (source.has("enabled") && !source.path("enabled").asBoolean())
                    || source.path("includes").size() != 0 || source.path("excludes").size() != 0
                    || !"_none".equals(settings.path("default_pipeline").asText("_none"))
                    || !"_none".equals(settings.path("final_pipeline").asText("_none"))
                    || !"request".equalsIgnoreCase(settings.path("translog").path("durability").asText("request"))
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
        destination.put("index", metadata.path("_index").asText());
        if (metadata.hasNonNull("routing")) {
            destination.put("routing", "=" + metadata.path("routing").asText());
        } else {
            destination.put("routing", "discard");
        }
        if (metadata.hasNonNull("pipeline")) {
            destination.put("pipeline", metadata.path("pipeline").asText());
        }
        byte[] body = json(Map.of(
            "source", Map.of("index", stagingIndex, "query", Map.of("ids", Map.of("values", List.of(attempt.uploadId)))),
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
        return writeJournal(attempt, journal, journal.source.deepCopy().put("phase", "publishing"), "CLAIM_PUBLICATION")
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
                        return writeJournal(attempt, publishing, publishing.source.deepCopy().put("taskId", taskId), "RECORD_TASK");
                    })
                    .flatMap(recorded -> finishPublication(attempt, recorded, true));
            });
    }

    private Mono<HttpResponse> finishPublication(Attempt attempt, Journal journal, boolean reportFailure) {
        // A timed wait can return an HTTP timeout error while the task is healthy. Poll status instead.
        String path = "_tasks/" + attempt.taskId;
        return Mono.defer(() -> request(attempt, "WAIT_PUBLICATION", HttpMethod.GET, path, null, true))
            .repeatWhen(repeat -> repeat.delayElements(Duration.ofMillis(200)))
            .filter(response -> {
                var result = parse(response.body);
                if (!result.path("completed").isBoolean()) {
                    throw failure(attempt, "WAIT_PUBLICATION", HttpMethod.GET, path, null,
                        new OpenSearchClient.OperationFailed("Invalid task status response", response));
                }
                return result.path("completed").asBoolean();
            })
            .next()
            .flatMap(response -> {
                var task = parse(response.body);
                var result = task.path("response");
                boolean success = successfulPublication(task);
                // A terminal task cannot publish later, including when it returned a mapping error.
                attempt.publicationMayHaveCommitted = success || task.hasNonNull("error")
                    || result.path("created").asLong() > 0 || result.path("updated").asLong() > 0;
                return writeJournal(attempt, journal, journal.source.deepCopy()
                    .put("phase", success ? "complete" : "failed"), "COMPLETE_PUBLICATION")
                    .flatMap(completed -> {
                        if (!success && reportFailure) {
                            return Mono.error(failure(attempt, "PUBLICATION_RESULT", HttpMethod.POST,
                                attempt.publicationPath == null ? "_reindex" : attempt.publicationPath, attempt.publicationBody,
                                new OpenSearchClient.OperationFailed(
                                    "Staged document publication did not index exactly one document", response)));
                        }
                        return !reportFailure && success ? cleanupUpload(attempt).thenReturn(response) : Mono.just(response);
                    });
            });
    }

    static boolean successfulPublication(JsonNode task) {
        var result = task.path("response");
        if (task.hasNonNull("error") || !result.path("timed_out").isBoolean() || result.path("timed_out").asBoolean()
            || !result.path("failures").isArray() || !result.path("failures").isEmpty()) {
            return false;
        }
        for (String counter : List.of("total", "created", "updated", "deleted", "version_conflicts", "noops")) {
            var value = result.path(counter);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
                return false;
            }
        }
        return result.path("total").asLong() == 1
            && ((result.path("created").asLong() == 1 && result.path("updated").asLong() == 0)
                || (result.path("created").asLong() == 0 && result.path("updated").asLong() == 1))
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
                    totals.attempts++;
                    totals.bytes += body == null ? 0 : body.length;
                    if (attempts[0]++ > 0) {
                        totals.retries++;
                    }
                }
                long start = System.nanoTime();
                // Staging requests are uncompressed, so the measured bytes are also the HTTP body bytes.
                return transport.asyncRequestBytesWithoutRetry(method, path, body, headers, context)
                    .doOnSuccess(response -> {
                        if (totals != null) {
                            totals.nanos += System.nanoTime() - start;
                        }
                    })
                    .doOnError(error -> {
                        if (totals != null) {
                            totals.nanos += System.nanoTime() - start;
                        }
                    })
                    .map(response -> {
                        if (response.statusCode < 200 || response.statusCode >= 300) {
                            throw new OpenSearchClient.OperationFailed("Staged document request failed: " + method + " " + path, response);
                        }
                        return response;
                    });
            });
            return retryable ? request.retryWhen(IDEMPOTENT_RETRY) : request;
        });
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

    @SuppressWarnings("java:S1172")
    private record Journal(ObjectNode source, long sequence, long primaryTerm) {}

    private static final class Attempt {
        final Scope scope;
        final IRfsContexts.IRequestContext context;
        final Totals totals = new Totals();
        String uploadId;
        Long chunkSequence;
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
    }
}
