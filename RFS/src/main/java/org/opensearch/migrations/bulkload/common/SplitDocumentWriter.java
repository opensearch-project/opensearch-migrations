package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.faileddocumentstream.SplitRequestFailure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * One logical index operation: conditionally replace the destination, assemble bounded parts in
 * its unmapped source field, then replace that source with the completed document. Every write is
 * conditional, every part is acknowledged before generating the next, and only completion
 * acknowledges the original operation. Replay starts with a new indexing replacement.
 */
final class SplitDocumentWriter {
    static final String STATE_FIELD = "_rfs_large_document";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final String DOCUMENT_ID = "_id";
    private static final String REQUIRE_ALIAS = "require_alias";
    private static final String PIPELINE = "pipeline";
    private static final String OBJECT = "object";
    private static final String INDEX = "_index";
    private static final String SOURCE = "_source";
    private static final String SEQUENCE = "_seq_no";
    private static final String TERM = "_primary_term";
    private static final String ROUTING = "routing";
    private static final String ENABLED = "enabled";
    private static final String NEXT = "next";
    private static final String ATTEMPT = "attempt";
    private static final String COMPLETE = "COMPLETE";
    private static final String COMPLETE_SCRIPT = loadScript();
    private static final Set<String> SUPPORTED_METADATA = Set.of(INDEX, DOCUMENT_ID, ROUTING, PIPELINE, REQUIRE_ALIAS);
    private static final Retry RETRY = Retry.backoff(4, Duration.ofMillis(200))
        .maxBackoff(Duration.ofSeconds(2)).filter(SplitDocumentWriter::retryable)
        .onRetryExhaustedThrow((spec, signal) -> signal.failure());

    private final RestClient transport;
    private final long limit;
    private final OperationSplitter splitter;
    private final Map<String, Mono<Target>> targets = new ConcurrentHashMap<>();

    SplitDocumentWriter(RestClient transport, long limit) {
        this(transport, limit, JsonDocumentChunks::split);
    }

    SplitDocumentWriter(RestClient transport, long limit, OperationSplitter splitter) {
        this.transport = transport;
        this.limit = limit;
        this.splitter = splitter;
    }

    Mono<OpenSearchClient.BulkResponse> write(
        String defaultIndex, BulkNdjson.EncodedOperation operation, IRfsContexts.IRequestContext context
    ) {
        return Mono.defer(() -> {
            var metadata = validateOperation(defaultIndex, operation);
            var attempt = new Attempt(metadata, context);
            return targets.computeIfAbsent(metadata.path(INDEX).asText(),
                    index -> Mono.defer(() -> prepareTarget(index)).cache())
                .flatMap(target -> {
                    attempt.target = target;
                    attempt.routing = target.routing(metadata);
                    return initialize(attempt);
                })
                .thenMany(splitter.split(operation, limit)
                    .onErrorMap(error -> failure(attempt, "ENCODE_PART", null, null, null, error))
                    // Do not generate the next part while the current request is in flight or retrying.
                    .concatMap(part -> writePart(attempt, part), 0))
                .then(Mono.defer(() -> complete(attempt)))
                .map(response -> new OpenSearchClient.BulkResponse(200, "OK", response.headers,
                    "{\"errors\":false,\"items\":[]}",
                    new MigrationMetrics.Operations(1, 0, operation.body().length),
                    new MigrationMetrics.Requests(1, attempt.requests, attempt.retries, attempt.bytes,
                        Duration.ofNanos(attempt.nanos).toMillis())))
                .onErrorMap(error -> error instanceof SplitDocumentException ? error
                    : failure(attempt, "PREPARE", null, null, null, error));
        });
    }

    private Mono<Void> initialize(Attempt attempt) {
        return readState(attempt).flatMap(previous -> {
            var body = json(Map.of(STATE_FIELD,
                Map.of(ATTEMPT, attempt.id, NEXT, 0, "stack", List.of(), "numbers", List.of())));
            String path = attempt.path("_doc") + "&pipeline=_none"
                + (previous.path("found").asBoolean() ? condition(previous) : "&op_type=create");
            // Never resend an unconditional replacement. Accept a lost ACK only by reading our own token back.
            return request(attempt, "INDEX", HttpMethod.PUT, path, body)
                .map(SplitDocumentWriter::position)
                .onErrorResume(error -> uncertainWrite(error) ? verifyApplied(attempt, 0, error) : Mono.error(error))
                .doOnNext(position -> attempt.position = position).then();
        });
    }

    private Mono<Void> writePart(Attempt attempt, OperationSplitter.Part part) {
        if (part.sequence() != attempt.partsCompleted) {
            return Mono.error(new IllegalStateException("Operation splitter returned an out-of-order part"));
        }
        String path = attempt.path("_update") + condition(attempt.position);
        var sends = new long[1];
        return Mono.defer(() -> {
            if (sends[0]++ > 0) {
                attempt.retries++;
            }
            return request(attempt, "PART", HttpMethod.POST, path, part.body())
                .map(SplitDocumentWriter::position)
                // A repeated CAS cannot apply twice. Read small progress fields to resolve a lost ACK.
                .onErrorResume(error -> uncertainWrite(error)
                    ? verifyApplied(attempt, part.sequence() + 1, error) : Mono.error(error));
        })
            .retryWhen(RETRY)
            .doOnNext(position -> {
                attempt.position = position;
                attempt.partsCompleted++;
            }).then();
    }

    private Mono<HttpResponse> complete(Attempt attempt) {
        var body = json(Map.of("script", Map.of("lang", "painless", "source", COMPLETE_SCRIPT,
            "params", Map.of("chunks", attempt.partsCompleted))));
        String path = attempt.path("_update") + condition(attempt.position);
        // Completion removes the token. An ambiguous ACK fails the batch; replay replaces the whole document.
        return request(attempt, COMPLETE, HttpMethod.POST, path, body).map(response -> {
            position(response);
            if (!"updated".equals(parse(response.body).path("result").asText())) {
                throw new OpenSearchClient.OperationFailed("Document completion did not return an updated document", response);
            }
            return response;
        }).onErrorMap(error -> failure(attempt, COMPLETE, HttpMethod.POST, path, body, error));
    }

    private Mono<JsonNode> verifyApplied(Attempt attempt, long expectedNext, Throwable original) {
        return readState(attempt)
            .onErrorMap(error -> {
                original.addSuppressed(error);
                return original;
            })
            .flatMap(observed -> {
                var state = observed.path(SOURCE).path(STATE_FIELD);
                return attempt.id.equals(state.path(ATTEMPT).asText()) && state.path(NEXT).asLong(-1) == expectedNext
                    ? Mono.just(validPosition(observed)) : Mono.error(original);
            });
    }

    private Mono<JsonNode> readState(Attempt attempt) {
        String path = attempt.path("_doc") + "&_source_includes=" + STATE_FIELD + "." + ATTEMPT + "," + STATE_FIELD + "." + NEXT;
        return request(attempt, "READ_STATE", HttpMethod.GET, path, null)
            .map(response -> parse(response.body))
            .onErrorResume(error -> hasStatus(error, 404) ? Mono.just(MAPPER.createObjectNode().put("found", false)) : Mono.error(error))
            .retryWhen(RETRY);
    }

    private JsonNode validateOperation(String defaultIndex, BulkNdjson.EncodedOperation operation) {
        if (!operation.upsert() || operation.body() == null
            || !(parse(operation.action()).get("index") instanceof ObjectNode metadata)
            || !(metadata.get(DOCUMENT_ID) instanceof TextNode id) || id.textValue().isEmpty()) {
            throw unsupported(operation, "Splitting requires a plain index operation with an explicit document ID.");
        }
        metadata.fieldNames().forEachRemaining(name -> {
            if (!SUPPORTED_METADATA.contains(name)) {
                throw unsupported(operation, "Splitting does not support action metadata '" + name + "'.");
            }
        });
        if (metadata.hasNonNull(PIPELINE) && !"_none".equals(metadata.path(PIPELINE).asText())) {
            throw unsupported(operation, "Splitting cannot preserve ingest pipeline semantics.");
        }
        if (!metadata.hasNonNull(INDEX)) {
            metadata.put(INDEX, defaultIndex);
        }
        if (!metadata.path(INDEX).isTextual() || metadata.path(INDEX).asText().isEmpty()
            || (metadata.has(REQUIRE_ALIAS) && !metadata.path(REQUIRE_ALIAS).isBoolean())) {
            throw unsupported(operation, "Invalid index or require_alias metadata.");
        }
        return metadata;
    }

    private OversizedDocumentException unsupported(BulkNdjson.EncodedOperation operation, String message) {
        return new OversizedDocumentException(operation.size(), limit, message);
    }

    private Mono<Target> prepareTarget(String name) {
        // Resolve once and pin every part to the same concrete index, including for an alias action.
        return send(null, HttpMethod.GET, encode(name), null).retryWhen(RETRY).flatMap(response -> {
            var indices = parse(response.body);
            Target selected = null;
            for (var entries = indices.fields(); entries.hasNext();) {
                var entry = entries.next();
                var alias = entry.getValue().path("aliases").path(name);
                if (entry.getKey().equals(name) || alias.path("is_write_index").asBoolean()
                    || (indices.size() == 1 && alias.isObject() && !alias.path("is_write_index").isBoolean())) {
                    if (selected != null) {
                        return Mono.error(new IllegalArgumentException("Ambiguous split document destination"));
                    }
                    validateTarget(entry.getValue());
                    selected = new Target(entry.getKey(), alias.isObject(), alias.path("index_routing").asText(alias.path(ROUTING).asText(null)));
                }
            }
            if (selected == null) {
                return Mono.error(new IllegalArgumentException("Splitting requires an existing concrete index or write alias"));
            }
            var target = selected;
            var mapping = json(Map.of("properties", Map.of(STATE_FIELD, Map.of("type", OBJECT, ENABLED, false))));
            return send(null, HttpMethod.PUT, encode(target.index) + "/_mapping", mapping).retryWhen(RETRY).thenReturn(target);
        });
    }

    private static void validateTarget(JsonNode index) {
        var mappings = index.path("mappings");
        var source = mappings.path(SOURCE);
        var settings = index.path("settings").path("index");
        if ((source.has(ENABLED) && !source.path(ENABLED).asBoolean())
            || source.path("includes").size() != 0 || source.path("excludes").size() != 0
            || settings.path("derived_source").path(ENABLED).asBoolean()) {
            throw new IllegalArgumentException("Splitting requires complete stored _source");
        }
        for (String pipeline : List.of("default_pipeline", "final_pipeline")) {
            if (!"_none".equals(settings.path(pipeline).asText("_none"))) {
                throw new IllegalArgumentException("Splitting requires a target without ingest pipelines");
            }
        }
        if (!"request".equals(settings.path("translog").path("durability").asText("request"))) {
            throw new IllegalArgumentException("Splitting requires request translog durability");
        }
        var field = mappings.path("properties").path(STATE_FIELD);
        if (!field.isMissingNode() && (!field.path(ENABLED).isBoolean() || field.path(ENABLED).asBoolean()
            || !OBJECT.equals(field.path("type").asText(OBJECT)))) {
            throw new IllegalArgumentException("Reserved assembly field has an incompatible mapping: " + STATE_FIELD);
        }
    }

    private Mono<HttpResponse> request(Attempt attempt, String stage, HttpMethod method, String path, byte[] body) {
        return send(attempt, method, path, body).onErrorMap(error -> failure(attempt, stage, method, path, body, error));
    }

    private Mono<HttpResponse> send(Attempt attempt, HttpMethod method, String path, byte[] body) {
        return Mono.defer(() -> {
            if (body != null && body.length > limit) {
                return Mono.error(new OversizedDocumentException(body.length, limit, "The split request envelope does not fit."));
            }
            long start = System.nanoTime();
            if (attempt != null) {
                attempt.requests++;
                attempt.bytes += body == null ? 0 : body.length;
            }
            // No implicit HTTP retries: conditional writes and readback handle ambiguous outcomes explicitly.
            return transport.asyncRequestBytesWithoutRetry(method, path, body, null, attempt == null ? null : attempt.context)
                .doOnEach(signal -> {
                    if (attempt != null && (signal.isOnNext() || signal.isOnError())) {
                        attempt.nanos += System.nanoTime() - start;
                    }
                })
                .map(response -> {
                    if (response.statusCode < 200 || response.statusCode >= 300) {
                        throw new OpenSearchClient.OperationFailed("Split document request failed: " + method + " " + path, response);
                    }
                    return response;
                });
        });
    }

    private static SplitDocumentException failure(
        Attempt attempt, String stage, HttpMethod method, String path, byte[] body, Throwable error
    ) {
        if (error instanceof SplitDocumentException split) {
            return split;
        }
        var response = responseOf(error);
        return new SplitDocumentException(new SplitRequestFailure(attempt.id, stage, attempt.partsCompleted,
            method == null ? null : method.name(), path, body == null ? null : new String(body, StandardCharsets.UTF_8),
            response == null ? null : response.statusCode, response == null ? null : response.body, error.getMessage(),
            COMPLETE.equals(stage) && (response == null || response.statusCode < 400 || response.statusCode >= 500)), error);
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

    private static boolean retryable(Throwable error) {
        var response = responseOf(error);
        if (response != null) {
            return response.statusCode == 429 || response.statusCode >= 500;
        }
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) {
                return false;
            }
        }
        return true;
    }

    private static boolean uncertainWrite(Throwable error) {
        return retryable(error) || hasStatus(error, 409);
    }

    private static JsonNode position(HttpResponse response) {
        try {
            return validPosition(parse(response.body));
        } catch (IllegalStateException | UncheckedIOException invalid) {
            var error = new OpenSearchClient.OperationFailed("Invalid document write acknowledgement", response);
            error.initCause(invalid);
            throw error;
        }
    }

    private static JsonNode validPosition(JsonNode response) {
        for (String field : List.of(SEQUENCE, TERM)) {
            var value = response.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < (field.equals(TERM) ? 1 : 0)) {
                throw new IllegalStateException("Document write did not return valid concurrency metadata");
            }
        }
        return response;
    }

    private static String condition(JsonNode position) {
        validPosition(position);
        return "&if_seq_no=" + position.path(SEQUENCE).asLong() + "&if_primary_term=" + position.path(TERM).asLong();
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String loadScript() {
        try (var stream = SplitDocumentWriter.class.getResourceAsStream("/large-documents/complete.painless")) {
            if (stream == null) {
                throw new IllegalStateException("Missing document completion script");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record Target(String index, boolean alias, String aliasRouting) {
        String routing(JsonNode metadata) {
            if (metadata.path(REQUIRE_ALIAS).asBoolean() && !alias) {
                throw new IllegalArgumentException("require_alias requires an alias destination");
            }
            String explicit = metadata.path(ROUTING).asText(null);
            if (aliasRouting != null && explicit != null && !aliasRouting.equals(explicit)) {
                throw new IllegalArgumentException("Explicit routing conflicts with alias routing");
            }
            return explicit == null ? aliasRouting : explicit;
        }
    }

    private static final class Attempt {
        final String id = UUID.randomUUID().toString();
        final JsonNode metadata;
        final IRfsContexts.IRequestContext context;
        Target target;
        String routing;
        JsonNode position;
        long partsCompleted;
        long requests;
        long retries;
        long bytes;
        long nanos;

        Attempt(JsonNode metadata, IRfsContexts.IRequestContext context) {
            this.metadata = metadata;
            this.context = context;
        }

        String path(String action) {
            return encode(target.index) + "/" + action + "/" + encode(metadata.path(DOCUMENT_ID).asText())
                + "?refresh=false" + (routing == null ? "" : "&routing=" + encode(routing));
        }
    }
}
