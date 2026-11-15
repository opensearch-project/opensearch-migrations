package org.opensearch.migrations.bulkload.common;

import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.BulkResponse;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.GzipPayloadRequestTransformer;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkErrorClass;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.opensearch.migrations.bulkload.common.OpenSearchClient.BULK_REQUEST_TRACE_LOGGER;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.DEFAULT_BULK_TRACE_MAX_BYTES;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.NoResponseException;

/**
 * Performs one bulk HTTP attempt, including slot acquisition, request assembly, compression, tracing, and timing.
 */
final class BulkAttemptSender {
    private static final Logger BULK_TRACE = LoggerFactory.getLogger(BULK_REQUEST_TRACE_LOGGER);

    private final RestClient client;
    private final CompressionMode compressionMode;
    private final BulkRuntime bulkRuntime;
    private final Function<String, String> bulkRequestPath;
    private volatile int bulkTraceMaxBytes = DEFAULT_BULK_TRACE_MAX_BYTES;

    BulkAttemptSender(
        RestClient client,
        CompressionMode compressionMode,
        BulkRuntime bulkRuntime,
        Function<String, String> bulkRequestPath
    ) {
        this.client = client;
        this.compressionMode = compressionMode;
        this.bulkRuntime = bulkRuntime;
        this.bulkRequestPath = bulkRequestPath;
    }

    void setBulkTraceMaxBytes(int newBulkTraceMaxBytes) {
        if (newBulkTraceMaxBytes < 0) {
            throw new IllegalArgumentException("bulkTraceMaxBytes must be >= 0, got " + newBulkTraceMaxBytes);
        }
        bulkTraceMaxBytes = newBulkTraceMaxBytes;
    }

    Mono<AssessedResponse> sendAttempt(
        String indexName,
        List<PreparedBulkOperation> pendingOps,
        BulkRequestExecutor.RequestTotals totals,
        IRfsContexts.IRequestContext context
    ) {
        return withBulkSlot(() -> {
            // Assemble and compress only after taking a slot, so queued batches retain neither request buffer.
            boolean retry = totals.attempts > 0;
            long requestStartNanos = System.nanoTime();
            long previousStartNanos =
                bulkRuntime.lastBulkRequestStartNanos.getAndAccumulate(requestStartNanos, Math::max);
            if (previousStartNanos > 0 && requestStartNanos > previousStartNanos) {
                bulkRuntime.bulkFlowMetrics.onRequestStartGap(
                    retry,
                    (requestStartNanos - previousStartNanos) / 1_000_000.0
                );
            }
            byte[] sentBytes;
            int bodyLength;
            MigrationMetrics.Operations target;
            long assembledNanos;
            if (gzipBulkBodies()) {
                try (var request = assemblePooled(pendingOps)) {
                    assembledNanos = System.nanoTime();
                    bodyLength = request.size();
                    target = request.operations();
                    var bodyBuffers = request.buffers();
                    sentBytes = GzipPayloadRequestTransformer.gzip(bodyBuffers);
                    traceRequest(
                        indexName,
                        totals.attempts + 1,
                        pendingOps.size(),
                        bodyBuffers,
                        sentBytes.length,
                        true
                    );
                }
            } else {
                var request = assemble(pendingOps);
                assembledNanos = System.nanoTime();
                var bodyBytes = request.bytes();
                bodyLength = bodyBytes.length;
                target = request.operations();
                sentBytes = bodyBytes;
                traceRequest(
                    indexName,
                    totals.attempts + 1,
                    pendingOps.size(),
                    bodyBytes,
                    sentBytes.length,
                    false
                );
            }
            if (totals.attempts == 0) {
                totals.target = target;
            }
            totals.attempts++;
            totals.bytes += bodyLength;
            long attempt = totals.attempts;
            if (attempt > 1) {
                bulkRuntime.bulkFlowMetrics.onDocumentsResent(pendingOps.size());
            }
            long compressedNanos = System.nanoTime();
            bulkRuntime.bulkFlowMetrics.onRequestPrepared(
                retry,
                (assembledNanos - requestStartNanos) / 1_000_000.0,
                (compressedNanos - assembledNanos) / 1_000_000.0
            );
            bulkRuntime.bulkFlowMetrics.onRequestBody(pendingOps.size(), bodyLength, sentBytes.length);
            long start = System.nanoTime();
            if (attempt > 1 && totals.lastAttemptEndNanos > 0) {
                bulkRuntime.bulkFlowMetrics.onAttemptGap((start - totals.lastAttemptEndNanos) / 1_000_000);
            }
            return postBulkRequest(indexName, sentBytes, context)
                .map(response -> {
                    totals.lastAttemptEndNanos = System.nanoTime();
                    long elapsed = System.nanoTime() - start;
                    totals.durationNanos += elapsed;
                    traceResponse(indexName, attempt, response, elapsed);
                    var assessed = assessResponse(response);
                    bulkRuntime.bulkFlowMetrics.onAttempt(
                        attemptOutcome(assessed),
                        attempt > 1,
                        Duration.ofNanos(elapsed).toMillis()
                    );
                    return assessed;
                })
                .doOnError(error -> {
                    totals.lastAttemptEndNanos = System.nanoTime();
                    long elapsed = System.nanoTime() - start;
                    totals.durationNanos += elapsed;
                    traceTransportError(indexName, attempt, error, elapsed);
                    bulkRuntime.bulkFlowMetrics.onAttempt(
                        attemptOutcome(error),
                        attempt > 1,
                        Duration.ofNanos(elapsed).toMillis()
                    );
                })
                .onErrorMap(error -> new NoResponseException(
                    error,
                    Duration.ofNanos(System.nanoTime() - start)
                ));
        });
    }

    private Mono<AssessedResponse> withBulkSlot(Supplier<Mono<AssessedResponse>> attempt) {
        var limit = bulkRuntime.bulkInFlightLimit;
        if (limit == null) {
            return runBulkAttempt(attempt);
        }
        return Mono.defer(() -> {
            long waitStart = System.nanoTime();
            return limit.acquire()
                .doOnNext(slot -> bulkRuntime.bulkFlowMetrics.onSlotWait(
                    Duration.ofNanos(System.nanoTime() - waitStart).toMillis()
                ));
        }).flatMap(slot -> runBulkAttempt(attempt)
            .doOnNext(assessed -> slot.release(limitSignal(assessed)))
            .doOnError(error -> slot.release(limitSignal(error)))
            .doFinally(signal -> slot.release(AdaptiveLimit.Signal.CANCELLED)));
    }

    private static Mono<AssessedResponse> runBulkAttempt(Supplier<Mono<AssessedResponse>> attempt) {
        return Mono.defer(attempt).subscribeOn(Schedulers.parallel());
    }

    static BulkNdjson.SerializedBulk assemble(List<PreparedBulkOperation> pending) {
        return BulkNdjson.assemble(pending, PreparedBulkOperation::encoded);
    }

    private static BulkNdjson.PooledSerializedBulk assemblePooled(List<PreparedBulkOperation> pending) {
        return BulkNdjson.assemblePooled(pending, PreparedBulkOperation::encoded);
    }

    private boolean gzipBulkBodies() {
        return CompressionMode.GZIP_BODY_COMPRESSION.equals(compressionMode);
    }

    private Mono<HttpResponse> postBulkRequest(
        String indexName,
        byte[] bodyBytes,
        IRfsContexts.IRequestContext context
    ) {
        var additionalHeaders = new HashMap<String, List<String>>();
        if (gzipBulkBodies()) {
            RestClient.addGzipRequestHeaders(additionalHeaders);
            RestClient.addGzipResponseHeaders(additionalHeaders);
        }
        var responseTimeout = bulkRuntime.bulkResponseTimeout;
        return responseTimeout == null
            ? client.postAsyncBytes(bulkRequestPath.apply(indexName), bodyBytes, additionalHeaders, context)
            : client.postAsyncBytes(
                bulkRequestPath.apply(indexName),
                bodyBytes,
                additionalHeaders,
                context,
                responseTimeout
            );
    }

    private void traceRequest(
        String indexName,
        long attempt,
        int operations,
        byte[] body,
        int sentBytes,
        boolean gzipped
    ) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        BULK_TRACE.atTrace()
            .setMessage("Bulk request attempt {} to index '{}': {} operations, {} bytes, {} bytes sent {}\n{}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(operations)
            .addArgument(body.length)
            .addArgument(sentBytes)
            .addArgument(gzipped ? "gzipped" : "uncompressed")
            .addArgument(() -> traceBody(body, bulkTraceMaxBytes))
            .log();
    }

    private void traceRequest(
        String indexName,
        long attempt,
        int operations,
        ByteBuffer[] body,
        int sentBytes,
        boolean gzipped
    ) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        int bodyBytes = Arrays.stream(body).mapToInt(ByteBuffer::remaining).sum();
        BULK_TRACE.atTrace()
            .setMessage("Bulk request attempt {} to index '{}': {} operations, {} bytes, {} bytes sent {}\n{}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(operations)
            .addArgument(bodyBytes)
            .addArgument(sentBytes)
            .addArgument(gzipped ? "gzipped" : "uncompressed")
            .addArgument(() -> traceBody(body, bulkTraceMaxBytes))
            .log();
    }

    private void traceResponse(String indexName, long attempt, HttpResponse response, long elapsedNanos) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        BULK_TRACE.atTrace()
            .setMessage("Bulk response to attempt {} for index '{}': status {} after {} ms\n{}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(response.statusCode)
            .addArgument(Duration.ofNanos(elapsedNanos).toMillis())
            .addArgument(() -> response.body == null
                ? null
                : traceBody(response.body.getBytes(StandardCharsets.UTF_8), bulkTraceMaxBytes))
            .log();
    }

    private static void traceTransportError(
        String indexName,
        long attempt,
        Throwable error,
        long elapsedNanos
    ) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        BULK_TRACE.atTrace()
            .setMessage("Bulk request attempt {} for index '{}' got no response after {} ms: {}: {}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(Duration.ofNanos(elapsedNanos).toMillis())
            .addArgument(error.getClass().getName())
            .addArgument(error.getMessage())
            .log();
    }

    static String traceBody(byte[] body, int limit) {
        if (limit == 0 || body.length <= limit) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(body, 0, limit, StandardCharsets.UTF_8)
            + "... [" + (body.length - limit) + " more bytes not logged]";
    }

    static String traceBody(ByteBuffer[] body, int configuredLimit) {
        int available = Arrays.stream(body).mapToInt(ByteBuffer::remaining).sum();
        int limit = configuredLimit == 0 ? available : Math.min(available, configuredLimit);
        var prefix = new byte[limit];
        int copied = 0;
        for (var segment : body) {
            if (copied == limit) {
                break;
            }
            var view = segment.duplicate();
            int bytes = Math.min(view.remaining(), limit - copied);
            view.get(prefix, copied, bytes);
            copied += bytes;
        }
        var text = new String(prefix, StandardCharsets.UTF_8);
        return limit == available ? text : text + "... [" + (available - limit) + " more bytes not logged]";
    }

    private static AssessedResponse assessResponse(HttpResponse response) {
        long startedNanos = System.nanoTime();
        var errorClass = BulkErrorClass.of(response);
        return new AssessedResponse(response, errorClass, System.nanoTime() - startedNanos);
    }

    static AdaptiveLimit.Signal limitSignal(HttpResponse response) {
        return limitSignal(assessResponse(response));
    }

    private static AdaptiveLimit.Signal limitSignal(AssessedResponse assessed) {
        var response = assessed.response();
        if (BulkErrorClass.isClusterBlock(response)
            || ((response.statusCode == 200 || response.statusCode == 201)
                && assessed.errorClass() == BulkErrorClass.UNAVAILABLE)) {
            return AdaptiveLimit.Signal.OTHER_FAILURE;
        }
        if (response.statusCode == 429) {
            return AdaptiveLimit.Signal.THROTTLED_429;
        }
        if (assessed.errorClass() == BulkErrorClass.THROTTLE) {
            return AdaptiveLimit.Signal.ADMISSION_REJECTED;
        }
        if (response.statusCode == HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
            return AdaptiveLimit.Signal.TIMEOUT;
        }
        return response.statusCode == HttpURLConnection.HTTP_OK || response.statusCode == HttpURLConnection.HTTP_CREATED
            ? AdaptiveLimit.Signal.SUCCESS
            : AdaptiveLimit.Signal.OTHER_FAILURE;
    }

    static AdaptiveLimit.Signal limitSignal(Throwable error) {
        return error instanceof NoResponseException noResponse && noResponse.isResponseTimeout()
            ? AdaptiveLimit.Signal.TIMEOUT
            : AdaptiveLimit.Signal.OTHER_FAILURE;
    }

    static BulkFlowMetrics.AttemptOutcome attemptOutcome(HttpResponse response) {
        return attemptOutcome(assessResponse(response));
    }

    private static BulkFlowMetrics.AttemptOutcome attemptOutcome(AssessedResponse assessed) {
        var response = assessed.response();
        int status = response.statusCode;
        if (status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_CREATED) {
            if (assessed.errorClass() == BulkErrorClass.THROTTLE) {
                return BulkFlowMetrics.AttemptOutcome.ADMISSION_REJECTED;
            }
            return new BulkResponse(status, null, null, response.body).reportsNoFailedOperations()
                ? BulkFlowMetrics.AttemptOutcome.OK
                : BulkFlowMetrics.AttemptOutcome.ITEM_ERRORS;
        }
        if (status == 429) {
            return BulkFlowMetrics.AttemptOutcome.THROTTLED_429;
        }
        if (status == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
            return BulkFlowMetrics.AttemptOutcome.PAYLOAD_413;
        }
        if (status >= 400 && status < 500) {
            return BulkFlowMetrics.AttemptOutcome.OTHER_4XX;
        }
        return status >= 500
            ? BulkFlowMetrics.AttemptOutcome.SERVER_5XX
            : BulkFlowMetrics.AttemptOutcome.OTHER_STATUS;
    }

    static BulkFlowMetrics.AttemptOutcome attemptOutcome(Throwable error) {
        for (var cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof ReadTimeoutException || cursor instanceof TimeoutException) {
                return BulkFlowMetrics.AttemptOutcome.TIMEOUT;
            }
            if (cursor instanceof java.net.ConnectException
                || cursor instanceof io.netty.channel.ConnectTimeoutException) {
                return BulkFlowMetrics.AttemptOutcome.CONNECT_FAILED;
            }
        }
        return BulkFlowMetrics.AttemptOutcome.CONNECTION_RESET;
    }

    record AssessedResponse(HttpResponse response, BulkErrorClass errorClass, long assessmentNanos) {}
}
