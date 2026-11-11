package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.BulkResponse;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson.EncodedOperation;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends one bulk index operation that is too large for a single request as several smaller requests,
 * using only bulk actions (no script, no temporary field or index):
 * <ol>
 *   <li>The first fragment replaces the document: a {@code create}, or when the ID already exists, an
 *       {@code index} conditional on the sequence number just read.</li>
 *   <li>Every later fragment is an {@code update} whose partial {@code doc} the target merges into the
 *       stored source.</li>
 * </ol>
 * Merges of the fragments are idempotent and commute, so a retried or delayed duplicate of step 2 changes
 * nothing. Only a replacement could discard merges that follow it, and neither form in step 1 can apply twice.
 *
 * <p>Every request goes through the caller's ordinary bulk path, so retries, compression, metrics and the
 * failed document stream behave as they do for any other operation. A fragment the target rejects stops
 * the sequence: the failure record carries the whole original operation, and the target keeps the fields
 * merged so far until that document is migrated again.
 */
@Slf4j
final class LargeDocumentSplit {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PLAIN_METADATA = Set.of("_index", "_id", "routing");
    private static final Set<String> CONFLICT = Set.of("version_conflict_engine_exception");
    private static final byte[] NO_CHANGE = "{\"doc\":{}}".getBytes(StandardCharsets.UTF_8);
    /** Room in each request for the widest action line, the {@code {"doc":…}} wrapper and both newlines. */
    private static final int REQUEST_OVERHEAD = 128;
    private static final int CONFLICT_RETRIES = 3;

    /** The caller's retrying bulk path, for one operation. */
    interface Bulk {
        Mono<BulkResponse> send(EncodedOperation operation, DocumentExceptionAllowlist allowlist);
    }

    private final ObjectNode metadata;
    private final List<byte[]> fragments;
    private final boolean fenced;
    private final Bulk bulk;
    private final DocumentExceptionAllowlist allowlist;
    private final DocumentExceptionAllowlist conflictsAllowed;

    private LargeDocumentSplit(
        ObjectNode metadata, List<byte[]> fragments, boolean fenced, Bulk bulk, DocumentExceptionAllowlist allowlist
    ) {
        this.metadata = metadata;
        this.fragments = fragments;
        this.fenced = fenced;
        this.bulk = bulk;
        this.allowlist = allowlist;
        var allowed = new HashSet<>(allowlist.getAllowedExceptionTypes());
        allowed.addAll(CONFLICT);
        this.conflictsAllowed = new DocumentExceptionAllowlist(allowed);
    }

    /**
     * @param fenced   whether the target honors {@code if_seq_no}; without it an existing document is
     *                 replaced unconditionally
     * @param getIndex fetches the target index with {@code flat_settings}
     * @return the last response received, carrying the request totals of the whole sequence; or empty,
     *         before anything is written, when the operation or its index cannot be split safely
     */
    static Mono<BulkResponse> send(
        EncodedOperation whole, long maxRequestBytes, boolean fenced,
        Supplier<Mono<HttpResponse>> getIndex, Bulk bulk, DocumentExceptionAllowlist allowlist
    ) {
        ObjectNode metadata;
        List<byte[]> fragments;
        try {
            var action = MAPPER.readTree(whole.action());
            var names = new HashSet<String>();
            action.path("index").fieldNames().forEachRemaining(names::add);
            if (!names.contains("_id") || !PLAIN_METADATA.containsAll(names)) {
                return declined(whole, "it is not an index operation with an explicit ID and no other options");
            }
            metadata = (ObjectNode) action.get("index");
            long room = maxRequestBytes - whole.action().length - REQUEST_OVERHEAD;
            fragments = JsonObjectSplitter.split(whole.body(), (int) Math.max(0, Math.min(room, Integer.MAX_VALUE)));
        } catch (IOException e) {
            return declined(whole, e.getMessage());
        }
        return getIndex.get()
            .map(LargeDocumentSplit::whyUnsuitable)
            .onErrorResume(e -> Mono.just("its index could not be read: " + e))
            .flatMap(reason -> {
                if (!reason.isEmpty()) {
                    return declined(whole, reason);
                }
                log.atInfo().setMessage("Sending {} ({} bytes) as {} bulk requests")
                    .addArgument(metadata).addArgument(whole.body().length).addArgument(fragments.size()).log();
                return new LargeDocumentSplit(metadata, fragments, fenced, bulk, allowlist).write();
            })
            // One document was written, whatever number of requests carried it.
            .map(last -> new BulkResponse(last.statusCode, last.statusText, last.headers, last.body,
                new MigrationMetrics.Operations(1, 0, whole.body().length), last.requests));
    }

    private static Mono<BulkResponse> declined(EncodedOperation whole, String reason) {
        log.atWarn().setMessage("{} exceeds the bulk request size and is sent whole, because {}")
            .addArgument(() -> new String(whole.action(), StandardCharsets.UTF_8))
            .addArgument(reason).log();
        return Mono.empty();
    }

    /**
     * A merge makes the target re-index the document from its stored source, so that source must be the
     * complete document, and nothing may rewrite a document on its way in.
     *
     * @return why the index cannot take merges, or an empty string when it can
     */
    private static String whyUnsuitable(HttpResponse response) {
        JsonNode indices = MissingNode.getInstance();
        try {
            if (response.statusCode == 200) {
                indices = MAPPER.readTree(response.body);
            }
        } catch (JsonProcessingException e) {
            // Reported below as unreadable.
        }
        if (indices.isEmpty()) {
            return "its index could not be read (HTTP " + response.statusCode + ")";
        }
        for (var index : indices) {
            var source = index.path("mappings").path("_source");
            var settings = index.path("settings");
            if (!source.path("enabled").asBoolean(true) || !source.path("includes").isEmpty()
                || !source.path("excludes").isEmpty()
                || settings.path("index.derived_source.enabled").asBoolean(false)) {
                return "its index does not store the complete _source";
            }
            if (!"_none".equals(settings.path("index.default_pipeline").asText("_none"))
                || !"_none".equals(settings.path("index.final_pipeline").asText("_none"))) {
                return "its index applies an ingest pipeline";
            }
        }
        return "";
    }

    private Mono<BulkResponse> write() {
        // A conflicting concurrent write to the same document should not fail a merge that can simply be redone.
        var merge = metadata.deepCopy().put("retry_on_conflict", CONFLICT_RETRIES);
        return send("create", metadata, fragments.get(0), conflictsAllowed)
            .flatMap(created -> status(created) == 409 ? replace(created, CONFLICT_RETRIES) : Mono.just(created))
            .flatMap(replaced -> !succeeded(replaced) ? Mono.just(replaced)
                : Flux.fromIterable(fragments.subList(1, fragments.size()))
                    .concatMap(fragment -> send("update", merge, asPartialDocument(fragment), allowlist), 1)
                    .takeUntil(merged -> !succeeded(merged))
                    .reduce(replaced, LargeDocumentSplit::plus));
    }

    /** The ID already exists, from an earlier attempt or an earlier migration: replace that document once. */
    private Mono<BulkResponse> replace(BulkResponse previous, int attemptsLeft) {
        if (!fenced) {
            return send("index", metadata, fragments.get(0), allowlist).map(written -> plus(previous, written));
        }
        // An empty partial update writes nothing and reports where the document currently is.
        return send("update", metadata, NO_CHANGE, allowlist).map(read -> plus(previous, read)).flatMap(read -> {
            if (!succeeded(read)) {
                return Mono.just(read);
            }
            var position = item(read);
            var conditional = metadata.deepCopy()
                .put("if_seq_no", position.required("_seq_no").asLong())
                .put("if_primary_term", position.required("_primary_term").asLong());
            // A conflict means the document moved after the read; only the last attempt reports it as a failure.
            boolean lastAttempt = attemptsLeft <= 1;
            return send("index", conditional, fragments.get(0), lastAttempt ? allowlist : conflictsAllowed)
                .map(written -> plus(read, written))
                .flatMap(written -> status(written) == 409 && !lastAttempt
                    ? replace(written, attemptsLeft - 1) : Mono.just(written));
        });
    }

    private Mono<BulkResponse> send(String type, ObjectNode actionMetadata, byte[] body, DocumentExceptionAllowlist allowed) {
        var action = MAPPER.createObjectNode().set(type, actionMetadata).toString();
        return bulk.send(new EncodedOperation(action.getBytes(StandardCharsets.UTF_8), body, true), allowed);
    }

    private static byte[] asPartialDocument(byte[] fragment) {
        var wrapped = new byte[fragment.length + 8];
        System.arraycopy(NO_CHANGE, 0, wrapped, 0, 7); // {"doc":
        System.arraycopy(fragment, 0, wrapped, 7, fragment.length);
        wrapped[wrapped.length - 1] = '}';
        return wrapped;
    }

    private static BulkResponse plus(BulkResponse previous, BulkResponse next) {
        return new BulkResponse(next.statusCode, next.statusText, next.headers, next.body,
            next.target, previous.requests.plus(next.requests));
    }

    /** The single item of a one-operation bulk response. */
    private static JsonNode item(BulkResponse response) {
        try {
            var item = MAPPER.readTree(response.body).path("items").path(0);
            return item.isEmpty() ? item : item.elements().next();
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    private static int status(BulkResponse response) {
        return item(response).path("status").asInt();
    }

    private static boolean succeeded(BulkResponse response) {
        return status(response) / 100 == 2;
    }
}
