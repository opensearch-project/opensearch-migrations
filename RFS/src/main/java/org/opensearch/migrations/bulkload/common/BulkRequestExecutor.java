package org.opensearch.migrations.bulkload.common;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.BulkResponse;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkErrorClass;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowStats;
import org.opensearch.migrations.bulkload.common.retry.BulkItemErrorKind;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.parsing.BulkResponseParser.ItemFailure;
import org.opensearch.migrations.parsing.BulkResponseParser.ItemPartition;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.opensearch.migrations.bulkload.common.OpenSearchClient.BULK_TRUNCATED_RESPONSE_MAX_LENGTH;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.FAILED_ATTEMPTS_BEFORE_SPLIT;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.MAX_ITEM_FAILURE_RETRIES;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.MAX_NO_RESPONSE_RETRIES;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.MAX_UNKNOWN_FAILURE_RETRIES;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.NoResponseException;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.TargetAuthenticationFailed;

final class BulkRequestExecutor {
    private static final ObjectMapper OBJECT_MAPPER = OpenSearchClient.OBJECT_MAPPER;
    private static final Logger log = LoggerFactory.getLogger(OpenSearchClient.class);

    private final FailedRequestsLogger failedRequestsLogger;
    private final FailedDocumentReporter failedDocumentReporter;
    private final BulkRuntime bulkRuntime;
    private final BulkAttemptSender attemptSender;

    BulkRequestExecutor(
        RestClient client,
        FailedRequestsLogger failedRequestsLogger,
        CompressionMode compressionMode,
        FailedDocumentReporter failedDocumentReporter,
        BulkRuntime bulkRuntime,
        UnaryOperator<String> bulkRequestPath,
        IntSupplier bulkTraceMaxBytes
    ) {
        this.failedRequestsLogger = failedRequestsLogger;
        this.failedDocumentReporter = failedDocumentReporter;
        this.bulkRuntime = bulkRuntime;
        this.attemptSender =
            new BulkAttemptSender(client, compressionMode, bulkRuntime, bulkRequestPath, bulkTraceMaxBytes);
    }

    public void setBulkRetryBudget(RetryBudget budget) {
        bulkRuntime.bulkRetryPolicy = new BulkRetryPolicy(budget);
        bulkRuntime.installBulkFlowMetrics();
    }

    /**
     * Report bulk flow-control events (retries, the retry budget, the in-flight limit, each attempt's outcome) to
     * {@code metrics} as well as to this client's own totals ({@link #getBulkFlowSummary}).
     */
    public void setBulkFlowMetrics(BulkFlowMetrics metrics) {
        bulkRuntime.bulkFlowMetrics = BulkFlowMetrics.both(bulkRuntime.bulkFlowStats, metrics);
        bulkRuntime.installBulkFlowMetrics();
    }

    /** Totals of this client's flow-control events, for the work-item metrics record. */
    public BulkFlowStats.Summary getBulkFlowSummary() {
        return bulkRuntime.bulkFlowStats.snapshot(bulkRuntime.bulkInFlightLimit);
    }

    public RetryBudget getBulkRetryBudget() {
        return bulkRuntime.bulkRetryPolicy.budget();
    }

    /**
     * How long each bulk attempt waits for its response once the request is sent, instead of the RestClient's
     * timeout (which work coordination and metadata requests keep). Null restores the RestClient's.
     */
    public void setBulkResponseTimeout(Duration bulkResponseTimeout) {
        if (bulkResponseTimeout != null && (bulkResponseTimeout.isZero() || bulkResponseTimeout.isNegative())) {
            throw new IllegalArgumentException("bulkResponseTimeout must be positive, got " + bulkResponseTimeout);
        }
        bulkRuntime.bulkResponseTimeout = bulkResponseTimeout;
    }

    public void setBulkInFlightLimit(AdaptiveLimit limit) {
        bulkRuntime.bulkInFlightLimit = limit;
        bulkRuntime.installBulkFlowMetrics();
    }

    public AdaptiveLimit getBulkInFlightLimit() {
        return bulkRuntime.bulkInFlightLimit;
    }

    private static String truncateMessageIfNeeded(String input, int maxCharacters) {
        if (input == null || input.length() <= maxCharacters) {
            return input;
        }
        int partLength = maxCharacters / 2;
        String head = input.substring(0, partLength);
        String tail = input.substring(input.length() - partLength);
        return head + "... [truncated] ..." + tail;
    }

    /**
     * Shared bulk request execution with retry, error handling, and compaction. A batch is retried, with the waits
     * of {@link BulkRetryPolicy}, until every operation has succeeded or been given up; it never gives up because of
     * time or an attempt count, and the work item's lease bounds it. Each attempt is classified by status code
     * before any body parsing:
     * <ul>
     *   <li><b>Throttle</b> (a 429, or a 2xx with items that failed with a 429 type): the throttled operations are
     *       retried. Never counted against anything; it also ends the batch's streaks.</li>
     *   <li><b>200/201</b>: successes leave {@code pendingOps}; non-retryable items are recorded in the failed
     *       document stream at once; an operation whose item fails for another retryable reason is retried, and
     *       given up after {@value #MAX_ITEM_FAILURE_RETRIES} retries. A body that can't be matched to the request
     *       retries everything.</li>
     *   <li><b>400/413</b>: the request fails the same way every time. A multi-operation batch is halved to isolate
     *       the operation responsible; a single operation is a terminal {@link FailureClass#NON_RETRYABLE}
     *       failure.</li>
     *   <li><b>401/403</b>: the target refused the credentials. Logged at ERROR, and {@link TargetAuthenticationFailed}
     *       stops the worker; nothing is recorded as failed.</li>
     *   <li><b>Any other status</b>, and <b>no response</b> (timeout, reset, premature close): retried. After
     *       {@value #FAILED_ATTEMPTS_BEFORE_SPLIT} such attempts in a row a multi-operation batch is halved. A single
     *       operation is given up after {@value #MAX_UNKNOWN_FAILURE_RETRIES} retries that got another status, or
     *       {@value #MAX_NO_RESPONSE_RETRIES} retries that got no response, in a row.</li>
     * </ul>
     * <p>Terminal failures are resolved by {@link #finishWithTerminalFailure}.
     *
     * @param indexName         target index
     * @param pendingOps        mutable list of pending operations (compacted on partial success)
     * @param context           request context for metrics
     * @param allowlist         exception types to treat as success
     */
    Mono<BulkResponse> executeBulkWithRetry(
        String indexName,
        List<PreparedBulkOperation> pendingOps,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        var totals = new RequestTotals();
        var retryState = new BulkRetryPolicy.Batch();
        return Mono.defer(() -> {
            if (totals.batchId == 0) {
                totals.batchId = bulkRuntime.nextBatchId.incrementAndGet();
                totals.openedNanos = System.nanoTime();
                bulkRuntime.bulkFlowMetrics.onBatchOpened(totals.batchId);
            }
            return attemptSender.sendAttempt(indexName, pendingOps, totals, context)
                .flatMap(assessed -> classifyWithMetrics(
                    true,
                    assessed.assessmentNanos(),
                    () -> classifyResponse(
                        indexName,
                        pendingOps,
                        totals,
                        assessed.response(),
                        assessed.errorClass(),
                        context,
                        allowlist
                    )
                ))
                .onErrorResume(NoResponseException.class, error -> classifyWithMetrics(
                    false,
                    0,
                    () -> classifyNoResponse(indexName, pendingOps, totals, error)
                ));
        })
        .retryWhen(retryStrategy.apply(retryState))
        // Only a test's limited schedule runs out; credentials the target refuses stop the worker.
        .onErrorResume(error -> !(error instanceof TargetAuthenticationFailed),
            error -> Mono.just(new AttemptOutcome.Exhausted(error, null)))
        .doOnNext(outcome -> bulkRuntime.bulkFlowMetrics.onBatchFinished(totals.attempts))
        .doFinally(signal -> {
            if (totals.batchId != 0) {
                bulkRuntime.bulkFlowMetrics.onBatchClosed(totals.batchId,
                    Duration.ofNanos(System.nanoTime() - totals.openedNanos).toMillis(), totals.longestThrottleStreak);
            }
        })
        .flatMap(outcome -> switch (outcome) {
            case AttemptOutcome.Completed completed -> {
                bulkRuntime.bulkRetryPolicy.onSuccess(retryState);
                yield Mono.just(withTotals(completed.response(), totals));
            }
            case AttemptOutcome.Split split -> {
                bulkRuntime.bulkFlowMetrics.onBatchSplit(split.reason());
                yield splitAndSend(
                    indexName,
                    pendingOps,
                    context,
                    allowlist,
                    totals,
                    split.description(),
                    retryStrategy
                );
            }
            case AttemptOutcome.Rejected rejected -> finishWithTerminalFailure(indexName, pendingOps, totals, context,
                FailureClass.NON_RETRYABLE,
                new OperationFailed(rejected.response().getFailureMessage(), rejected.response()), null);
            case AttemptOutcome.Exhausted exhausted -> finishWithTerminalFailure(indexName, pendingOps, totals, context,
                FailureClass.RETRYABLE_EXHAUSTED, exhausted.error(), exhausted.reason());
        });
    }

    private Mono<AttemptOutcome> classifyWithMetrics(
        boolean responseReceived,
        long assessmentNanos,
        Supplier<Mono<AttemptOutcome>> classification
    ) {
        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            return classification.get().doFinally(signal -> bulkRuntime.bulkFlowMetrics.onResponseClassified(
                responseReceived,
                (assessmentNanos + System.nanoTime() - startedNanos) / 1_000_000.0
            ));
        });
    }

    /** The result of a bulk attempt that ends the retry loop; anything retryable is an error signal instead. */
    private sealed interface AttemptOutcome {
        /** Every pending operation succeeded, was allowlisted, or was routed to the failed document stream. */
        record Completed(BulkResponse response) implements AttemptOutcome {}
        /** Halve the batch and send each half on its own. */
        record Split(BulkFlowMetrics.SplitReason reason, String description) implements AttemptOutcome {}
        /** The target rejected a single-operation request outright (400/413). */
        record Rejected(BulkResponse response) implements AttemptOutcome {}
        /** The documents are given up for {@code reason}; null when a test's limited retry schedule ran out. */
        record Exhausted(Throwable error, BulkFlowMetrics.GiveUpReason reason) implements AttemptOutcome {}
    }

    private Mono<AttemptOutcome> classifyResponse(
        String indexName,
        List<PreparedBulkOperation> pendingOps,
        RequestTotals totals,
        HttpResponse response,
        BulkErrorClass errorClass,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist
    ) {
        boolean success = response.statusCode >= 200 && response.statusCode < 300;
        // Any response ends a no-response streak; a 2xx, or a response that blames the target, ends the others.
        totals.consecutiveNoResponses = 0;
        totals.noResponseFailures = 0;
        if (success || errorClass.isTargetState()) {
            totals.consecutiveWholeFailures = 0;
            totals.unknownFailures = 0;
        }
        var resp = new BulkResponse(response.statusCode, response.statusText, response.headers, response.body);
        if (!resp.hasBadStatusCode()) {
            totals.countThrottle(errorClass == BulkErrorClass.THROTTLE);
            if (resp.reportsNoFailedOperations()) {
                bulkRuntime.bulkFlowMetrics.onWaitingOnTarget(totals.batchId, 0);
                return Mono.just(new AttemptOutcome.Completed(resp));
            }
            log.atInfo()
                .setMessage("Bulk response on index '{}' contains errors: {}")
                .addArgument(indexName)
                .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();

            int successCount = compactPendingDocs(indexName, pendingOps, resp, context, allowlist, totals);

            if (pendingOps.isEmpty()) {
                bulkRuntime.bulkFlowMetrics.onWaitingOnTarget(totals.batchId, 0);
                return Mono.just(new AttemptOutcome.Completed(resp));
            }
            bulkRuntime.bulkFlowMetrics.onWaitingOnTarget(
                totals.batchId, errorClass.isTargetState() ? pendingOps.size() : 0);
            log.atWarn()
                .setMessage("After bulk request attempt {} on index '{}', {} more documents have succeeded, {} remain. The error response message was: {}")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(successCount)
                .addArgument(pendingOps::size)
                .addArgument(truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();
            return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
        }
        if (isNonRetryableRequestStatus(resp.statusCode)) {
            log.atWarn()
                .setMessage("Bulk request attempt {} on index '{}' with {} documents was rejected with status {}; {}. Response: {}")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(pendingOps::size)
                .addArgument(resp.statusCode)
                .addArgument(pendingOps.size() > 1 ? "splitting the batch" : "not retrying")
                .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();
            return Mono.just(pendingOps.size() > 1
                ? new AttemptOutcome.Split(resp.statusCode == HttpURLConnection.HTTP_ENTITY_TOO_LARGE
                    ? BulkFlowMetrics.SplitReason.TOO_LARGE : BulkFlowMetrics.SplitReason.STATUS,
                    "status " + resp.statusCode)
                : new AttemptOutcome.Rejected(resp));
        }
        var failed = new OperationFailed(resp.getFailureMessage(), resp);
        totals.countThrottle(errorClass == BulkErrorClass.THROTTLE);
        bulkRuntime.bulkFlowMetrics.onWaitingOnTarget(
            totals.batchId, errorClass.isTargetState() ? pendingOps.size() : 0);
        if (errorClass == BulkErrorClass.THROTTLE) {
            log.atInfo()
                .setMessage("Bulk request attempt {} on index '{}' was throttled (status {}); retrying all {} documents")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(resp.statusCode)
                .addArgument(pendingOps::size)
                .log();
            return Mono.error(failed);
        }
        if (errorClass == BulkErrorClass.UNAVAILABLE) {
            var reason = BulkErrorClass.isClusterBlock(response) ? "cluster_block_exception" : "http_" + resp.statusCode;
            bulkRuntime.bulkFlowMetrics.onTargetUnavailable(reason, pendingOps.size());
            log.atWarn()
                .setMessage("Bulk request attempt {} on index '{}' found the target unable to take writes (status {}, "
                    + "{}); retrying all {} documents until it can, without counting it against them. Response: {}")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(resp.statusCode)
                .addArgument(reason)
                .addArgument(pendingOps::size)
                .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();
            return Mono.error(failed);
        }
        if (errorClass == BulkErrorClass.AUTH) {
            log.atError()
                .setMessage("The target refused this worker's credentials (status {}) for a bulk request on index '{}'. "
                    + "Stopping: no documents are recorded as failed, and the work item is retried once its lease "
                    + "expires. Check the target's credentials and permissions. Response: {}")
                .addArgument(resp.statusCode)
                .addArgument(indexName)
                .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();
            return Mono.error(new TargetAuthenticationFailed(resp.statusCode, failed));
        }
        // An unknown status is retried a fixed number of times. A multi-document batch is split to isolate a
        // possible poison document; a single document is eventually recorded and the migration moves on.
        boolean single = pendingOps.size() == 1;
        String action;
        AttemptOutcome outcome = null;
        if (single) {
            totals.unknownFailures++;
            if (totals.unknownFailures > MAX_UNKNOWN_FAILURE_RETRIES) {
                outcome = new AttemptOutcome.Exhausted(failed, BulkFlowMetrics.GiveUpReason.UNKNOWN_FAILURES);
                action = "giving the document up";
            } else {
                action = "retrying (" + totals.unknownFailures + " of "
                    + (MAX_UNKNOWN_FAILURE_RETRIES + 1) + " failures)";
            }
        } else {
            totals.consecutiveWholeFailures++;
            if (totals.consecutiveWholeFailures >= FAILED_ATTEMPTS_BEFORE_SPLIT) {
                outcome = new AttemptOutcome.Split(BulkFlowMetrics.SplitReason.STATUS, "status " + resp.statusCode);
                action = "splitting the batch";
            } else {
                action = "retrying all " + pendingOps.size() + " documents";
            }
        }
        log.atWarn()
            .setMessage("Bulk request attempt {} on index '{}' failed with status {}; {}. Response: {}")
            .addArgument(totals.attempts)
            .addArgument(indexName)
            .addArgument(resp.statusCode)
            .addArgument(action)
            .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
            .log();
        return outcome != null ? Mono.just(outcome) : Mono.error(failed);
    }

    /** 400 and 413 describe the request itself, so resending the same bytes can't succeed. */
    private static boolean isNonRetryableRequestStatus(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_BAD_REQUEST || statusCode == HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
    }

    private Mono<AttemptOutcome> classifyNoResponse(
        String indexName,
        List<PreparedBulkOperation> pendingOps,
        RequestTotals totals,
        NoResponseException error
    ) {
        totals.countThrottle(false);
        if (!error.isResponseTimeout()) {
            // The connection couldn't be made, or was reset or closed: the target, not a document, is the problem.
            var reason = BulkAttemptSender.attemptOutcome(error).label();
            bulkRuntime.bulkFlowMetrics.onTargetUnavailable(reason, pendingOps.size());
            bulkRuntime.bulkFlowMetrics.onWaitingOnTarget(totals.batchId, pendingOps.size());
            log.atWarn()
                .setMessage("Bulk request attempt {} on index '{}' with {} documents got no response after {} ms "
                    + "({}): {}; retrying, without counting it against them")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(pendingOps::size)
                .addArgument(error.elapsed::toMillis)
                .addArgument(reason)
                .addArgument(() -> String.valueOf(error.getCause()))
                .log();
            return Mono.error(error);
        }
        bulkRuntime.bulkFlowMetrics.onWaitingOnTarget(totals.batchId, 0);
        // A response timeout is retried a fixed number of times. Split a multi-document batch to isolate a possible
        // poison document; give up a single document after its retry limit so the migration can move on.
        boolean single = pendingOps.size() == 1;
        totals.consecutiveNoResponses++;
        boolean split = !single && totals.consecutiveNoResponses >= FAILED_ATTEMPTS_BEFORE_SPLIT;
        totals.noResponseFailures = single ? totals.noResponseFailures + 1 : 0;
        boolean exhausted = single && totals.noResponseFailures > MAX_NO_RESPONSE_RETRIES;
        log.atWarn()
            .setMessage("Bulk request attempt {} on index '{}' with {} documents got no response after {} ms "
                + "(timed out{}; {} in a row): {}; {}")
            .addArgument(totals.attempts)
            .addArgument(indexName)
            .addArgument(pendingOps::size)
            .addArgument(error.elapsed::toMillis)
            .addArgument(() -> Optional.ofNullable(bulkRuntime.bulkResponseTimeout)
                .map(t -> " at " + t.toMillis() + " ms")
                .orElse(""))
            .addArgument(totals.consecutiveNoResponses)
            .addArgument(() -> String.valueOf(error.getCause()))
            .addArgument(split ? "splitting the batch" : exhausted ? "giving the document up" : "retrying")
            .log();
        if (split) {
            return Mono.just(new AttemptOutcome.Split(BulkFlowMetrics.SplitReason.NO_RESPONSE, "no response"));
        }
        if (exhausted) {
            return Mono.just(new AttemptOutcome.Exhausted(error, BulkFlowMetrics.GiveUpReason.NO_RESPONSE));
        }
        return Mono.error(error);
    }

    /**
     * Send each half of {@code pendingOps} as its own batch, in order, each with a fresh retry budget. The attempts
     * made before the split count as retries; the halves account for the batch's operations.
     */
    private Mono<BulkResponse> splitAndSend(
        String indexName,
        List<PreparedBulkOperation> pendingOps,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        RequestTotals totals,
        String reason,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        int middle = pendingOps.size() / 2;
        var halves = List.<List<PreparedBulkOperation>>of(
            new ArrayList<>(pendingOps.subList(0, middle)),
            new ArrayList<>(pendingOps.subList(middle, pendingOps.size())));
        log.atWarn()
            .setMessage("Splitting a bulk request on index '{}' of {} documents into {} and {} ({})")
            .addArgument(indexName)
            .addArgument(pendingOps::size)
            .addArgument(middle)
            .addArgument(pendingOps.size() - middle)
            .addArgument(reason)
            .log();
        var beforeSplit = new MigrationMetrics.Requests(0, totals.attempts, totals.attempts, totals.bytes,
            Duration.ofNanos(totals.durationNanos).toMillis());
        return Flux.fromIterable(halves)
            .concatMap(half -> executeBulkWithRetry(indexName, half, context, allowlist, retryStrategy), 1)
            .reduce(BulkRequestExecutor::combine)
            .map(combined -> new BulkResponse(combined.statusCode, combined.statusText, combined.headers,
                combined.body, combined.target, beforeSplit.plus(combined.requests),
                totals.failures.plus(combined.failures)));
    }

    static BulkResponse combine(BulkResponse previous, BulkResponse next) {
        return new BulkResponse(next.statusCode, next.statusText, next.headers, next.body,
            previous.target.plus(next.target), previous.requests.plus(next.requests),
            previous.failures.plus(next.failures));
    }

    /**
     * Resolve operations that can't be written. When a failed document stream is configured, the operations are
     * recorded there and the batch completes, so the migration moves past them instead of failing the work item and
     * replaying the same documents forever. Without a stream there is nowhere to keep them, so the error propagates
     * and the work item fails, as before.
     *
     * <p>Recognized target-state failures never reach this method. Unknown statuses, response timeouts, and unknown
     * item errors reach it only after their fixed retry threshold, so the migration moves past a poison document
     * instead of retrying it indefinitely.
     */
    private Mono<BulkResponse> finishWithTerminalFailure(
        String indexName,
        List<PreparedBulkOperation> pendingOps,
        RequestTotals totals,
        IRfsContexts.IRequestContext context,
        FailureClass failureClass,
        Throwable error,
        BulkFlowMetrics.GiveUpReason reason
    ) {
        if (pendingOps.isEmpty()) {
            log.atError()
                .setCause(error)
                .setMessage("Unexpected empty document list for bulk request on index {}")
                .addArgument(indexName)
                .log();
            return Mono.error(error);
        }
        failedRequestsLogger.logBulkFailure(
            indexName,
            pendingOps::size,
            () -> new String(BulkAttemptSender.assemble(pendingOps).bytes(), StandardCharsets.UTF_8),
            error
        );
        // Documents are only given up after their fixed retry threshold, or for a non-retryable failure. A retry
        // schedule that runs out before either condition (only a test's limited schedule can) fails the work item.
        boolean targetAccepting = failureClass == FailureClass.NON_RETRYABLE || reason != null;
        if (reason != null) {
            bulkRuntime.bulkFlowMetrics.onGaveUp(reason, pendingOps.size());
        }
        if (!targetAccepting) {
            log.atError()
                .setMessage("Bulk request on index '{}' for {} documents stopped after {} attempts without "
                    + "reaching a document give-up condition; failing the work item so it is retried later")
                .addArgument(indexName)
                .addArgument(pendingOps::size)
                .addArgument(totals.attempts)
                .log();
            return Mono.error(error);
        }
        // Both a rejection and a give-up are about the whole request (items that fail on their own are recorded as
        // they're given up, in compactPendingDocs), so every operation shares the request-level failure. Without a
        // stream they are classified and charged the same way and the batch moves on, unrecorded.
        var sink = failedDocumentReporter.getSink();
        var failure = wholeRequestFailure(findOperationFailed(error).map(f -> f.response).orElse(null), error);
        for (var op : pendingOps) {
            failedDocumentReporter.emit(indexName, op.operation().get(), failure, failureClass);
        }
        countFailedDocuments(context, totals, failureClass, pendingOps.size());
        log.atError()
            .setMessage("Giving up on {} documents for index '{}' after {} attempts ({}); {} and the migration "
                + "continues")
            .addArgument(pendingOps::size)
            .addArgument(indexName)
            .addArgument(totals.attempts)
            .addArgument(failureClass)
            .addArgument(() -> sink != null
                ? "they are being recorded in the failed document stream at " + sink.getLocation()
                : "with no failed document stream configured they are not recorded anywhere")
            .log();
        var lastResponse = findOperationFailed(error).map(f -> f.response).orElse(null);
        var response = lastResponse != null
            ? new BulkResponse(lastResponse.statusCode, lastResponse.statusText, lastResponse.headers, lastResponse.body)
            : new BulkResponse(0, "No response", Map.of(), "");
        return Mono.just(withTotals(response, totals));
    }

    /** Item error types reported as themselves; any other type is reported as {@value #OTHER_ITEM_ERROR}. */
    static final java.util.Set<String> REPORTED_ITEM_ERROR_TYPES = java.util.stream.Stream.concat(
        org.opensearch.migrations.BulkDocErrorTypes.NON_RETRYABLE.stream(),
        java.util.stream.Stream.of("es_rejected_execution_exception", "rejected_execution_exception",
            "unavailable_shards_exception", "circuit_breaking_exception", "cluster_block_exception",
            "process_cluster_event_timeout_exception", "timeout_exception", "parse_exception"))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    static final String OTHER_ITEM_ERROR = "other";

    private void countItemErrors(List<ItemFailure> failures, boolean retryable) {
        if (failures.isEmpty()) {
            return;
        }
        var byType = new HashMap<String, Long>();
        for (var failure : failures) {
            var type = failure.getErrorType();
            byType.merge(type != null && REPORTED_ITEM_ERROR_TYPES.contains(type) ? type : OTHER_ITEM_ERROR, 1L,
                Long::sum);
        }
        byType.forEach((type, count) -> bulkRuntime.bulkFlowMetrics.onItemErrors(type, retryable, count));
    }

    /**
     * Every terminal failure passes through here to be counted: in metrics, and in {@code totals} (when the failure
     * belongs to a batch) for the batch's committed metrics.
     */
    private static void countFailedDocuments(IRfsContexts.IRequestContext context, RequestTotals totals,
                                             FailureClass failureClass, long count) {
        if (count <= 0) {
            return;
        }
        if (context != null) {
            context.addBulkDocumentsFailed(failureClass.name(), count);
        }
        if (totals != null) {
            totals.failures = totals.failures.plus(FailedDocumentReporter.failuresOf(failureClass, count));
        }
    }

    private static BulkResponse withTotals(BulkResponse response, RequestTotals totals) {
        return new BulkResponse(response.statusCode, response.statusText, response.headers, response.body,
            totals.target,
            new MigrationMetrics.Requests(
                1, totals.attempts, Math.max(0, totals.attempts - 1),
                totals.bytes, Duration.ofNanos(totals.durationNanos).toMillis()),
            totals.failures);
    }

    static final class RequestTotals {
        long attempts;
        long bytes;
        long durationNanos;
        int consecutiveNoResponses;
        // Counting toward giving documents up (see executeBulkWithRetry).
        int consecutiveWholeFailures;
        int unknownFailures;
        int noResponseFailures;
        final java.util.Map<PreparedBulkOperation, Integer> itemFailures = new java.util.IdentityHashMap<>();
        int throttleStreak;
        int longestThrottleStreak;
        long batchId;
        long openedNanos;
        // When the batch's last attempt ended, for the time until its next one is sent.
        long lastAttemptEndNanos;

        void countThrottle(boolean throttled) {
            throttleStreak = throttled ? throttleStreak + 1 : 0;
            longestThrottleStreak = Math.max(longestThrottleStreak, throttleStreak);
        }
        MigrationMetrics.Operations target = MigrationMetrics.Operations.ZERO;
        // Operations of this batch (not of the batches split from it) that became terminal failures.
        MigrationMetrics.Failures failures = MigrationMetrics.Failures.ZERO;
    }

    /**
     * Partitions the bulk response into success / non-retryable / retryable buckets,
     * emits one failed document stream record per non-retryable failure, and compacts {@code pendingDocs}
     * in place so it contains only the items that should be retried.
     *
     * <p>Items of kind {@link BulkItemErrorKind#DOCUMENT} are written to the failed document stream immediately and
     * removed from {@code pendingDocs} so the retry loop does not keep hammering them. Items whose error is about the
     * target ({@link BulkItemErrorKind#isTargetState()}) stay until they succeed. An item of kind
     * {@link BulkItemErrorKind#UNKNOWN} is given up once it has failed
     * {@value #MAX_ITEM_FAILURE_RETRIES} + 1 times.
     *
     * @return number of documents removed because they succeeded (or were allowlisted)
     */
    private int compactPendingDocs(
        String indexName,
        List<PreparedBulkOperation> pendingDocs,
        BulkResponse resp,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        RequestTotals totals
    ) {
        ItemPartition partition = BulkResponseParser.partitionItems(resp.statusCode, resp.body, allowlist);
        if (partition == null) {
            // No trustworthy per-item result - assume all failed, retry all
            return 0;
        }
        if (partition.itemCount() != pendingDocs.size()) {
            // Items can only be matched to operations by position, so a response that doesn't have exactly one item
            // per operation can't be trusted for any of them. Retry all rather than treat missing items as successes.
            log.atWarn()
                .setMessage("Bulk response on index '{}' has {} items for {} operations; retrying all of them")
                .addArgument(indexName)
                .addArgument(partition::itemCount)
                .addArgument(pendingDocs::size)
                .log();
            return 0;
        }

        // Snapshot the ops we're about to drop so we can emit failed document stream records before mutating.
        for (ItemFailure failure : partition.getNonRetryableFailures()) {
            BulkOperationSpec op = failure.getPosition() < pendingDocs.size()
                ? pendingDocs.get(failure.getPosition()).operation().get()
                : null;
            failedDocumentReporter.emit(indexName, op, failure, FailureClass.NON_RETRYABLE);
        }
        countFailedDocuments(context, totals, FailureClass.NON_RETRYABLE, partition.getNonRetryableFailures().size());
        countItemErrors(partition.getNonRetryableFailures(), false);
        countItemErrors(partition.getRetryableFailures(), true);
        var retryableFailures = partition.getRetryableFailures();
        retryableFailures.stream().filter(f -> f.getKind() == BulkItemErrorKind.UNAVAILABLE)
            .collect(java.util.stream.Collectors.groupingBy(ItemFailure::getErrorType,
                java.util.stream.Collectors.counting()))
            .forEach(bulkRuntime.bulkFlowMetrics::onTargetUnavailable);

        // Keep only retryable failures in pendingDocs, in original order. A document whose item keeps failing with an
        // unknown error is given up after MAX_ITEM_FAILURE_RETRIES retries; target-state errors never count.
        BitSet keep = new BitSet(pendingDocs.size());
        BitSet givenUp = new BitSet(pendingDocs.size());
        for (ItemFailure failure : retryableFailures) {
            var op = pendingDocs.get(failure.getPosition());
            if (failure.getKind() == BulkItemErrorKind.UNKNOWN
                && totals.itemFailures.merge(op, 1, Integer::sum) > MAX_ITEM_FAILURE_RETRIES) {
                givenUp.set(failure.getPosition());
            } else {
                keep.set(failure.getPosition());
            }
        }
        int itemsGivenUp = givenUp.cardinality();
        for (ItemFailure failure : partition.getRetryableFailures()) {
            if (givenUp.get(failure.getPosition())) {
                failedDocumentReporter.emit(indexName, pendingDocs.get(failure.getPosition()).operation().get(),
                    failure, FailureClass.RETRYABLE_EXHAUSTED);
            }
        }
        if (itemsGivenUp > 0) {
            countFailedDocuments(context, totals, FailureClass.RETRYABLE_EXHAUSTED, itemsGivenUp);
            bulkRuntime.bulkFlowMetrics.onGaveUp(BulkFlowMetrics.GiveUpReason.ITEM_FAILURES, itemsGivenUp);
            log.atError()
                .setMessage("Giving up on {} documents for index '{}' whose items failed {} times; they are being "
                    + "recorded in the failed document stream and the migration continues")
                .addArgument(itemsGivenUp)
                .addArgument(indexName)
                .addArgument(MAX_ITEM_FAILURE_RETRIES + 1)
                .log();
        }
        int writeIdx = 0;
        for (int i = keep.nextSetBit(0); i >= 0; i = keep.nextSetBit(i + 1)) {
            pendingDocs.set(writeIdx++, pendingDocs.get(i));
        }
        int removed = pendingDocs.size() - writeIdx;
        pendingDocs.subList(writeIdx, pendingDocs.size()).clear();
        // removed = successes + non-retryable + given up; return only the success count for the existing log line.
        return removed - partition.getNonRetryableFailures().size() - itemsGivenUp;
    }

    private static Optional<OperationFailed> findOperationFailed(Throwable error) {
        for (var cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof OperationFailed opFailed) {
                return Optional.of(opFailed);
            }
        }
        return Optional.empty();
    }

    /**
     * A failure that applies to every operation in a request: an error status ({@code failureType} {@code http_<status>},
     * with the truncated body) or no response at all ({@code failureType} {@code no_response}, with the exception).
     */
    static ItemFailure wholeRequestFailure(HttpResponse response, Throwable error) {
        var detail = OBJECT_MAPPER.createObjectNode();
        String failureType;
        if (response != null) {
            failureType = "http_" + response.statusCode;
            detail.put("status", response.statusCode);
            detail.put("body", truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH));
        } else {
            failureType = "no_response";
            Throwable transportError = error;
            for (var cursor = error; cursor != null; cursor = cursor.getCause()) {
                transportError = cursor;
                if (cursor instanceof NoResponseException && cursor.getCause() != null) {
                    transportError = cursor.getCause();
                    break;
                }
            }
            detail.put("exception", transportError != null ? transportError.getClass().getName() : null);
            detail.put("message", transportError != null ? transportError.getMessage() : null);
        }
        return new ItemFailure(0, null, failureType, detail.toString());
    }


}
