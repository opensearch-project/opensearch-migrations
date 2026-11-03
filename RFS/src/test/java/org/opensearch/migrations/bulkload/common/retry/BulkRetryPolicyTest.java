package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class BulkRetryPolicyTest {
    private static final Duration CAP = BulkRetryPolicy.DEFAULT_MAX_BACKOFF;

    private static OpenSearchClient.OperationFailed failed(int status, Map<String, String> headers, String body) {
        return new OpenSearchClient.OperationFailed("failed", new HttpResponse(status, "", headers, body));
    }

    private static OpenSearchClient.OperationFailed failed(int status) {
        return failed(status, Map.of(), "{}");
    }

    /** Fails with {@code failure} for the first {@code failures} subscriptions, then emits "ok". */
    private static Mono<String> failing(int failures, Throwable failure, AtomicInteger attempts) {
        return Mono.defer(() -> attempts.incrementAndGet() <= failures ? Mono.error(failure) : Mono.just("ok"));
    }

    @Test
    void waitsAreFullJitterOverADoublingBackoffCappedAtTheMax() {
        var policy = new BulkRetryPolicy(new RetryBudget(), () -> 0.5);
        Assertions.assertEquals(1_000, policy.backoffMillis(0, CAP));
        Assertions.assertEquals(2_000, policy.backoffMillis(1, CAP));
        Assertions.assertEquals(8_000, policy.backoffMillis(3, CAP));
        Assertions.assertEquals(15_000, policy.backoffMillis(4, CAP));
        Assertions.assertEquals(15_000, policy.backoffMillis(40, CAP));
        Assertions.assertEquals(0, new BulkRetryPolicy(new RetryBudget(), () -> 0.0).backoffMillis(3, CAP));
    }

    @Test
    void retriesUntilSuccessAndTheCompanionEmitsRatherThanCompletingEmpty() {
        var attempts = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> {
                var policy = new BulkRetryPolicy(new RetryBudget(), () -> 0.5);
                return failing(3, failed(503), attempts)
                    .retryWhen(policy.retryFor(new BulkRetryPolicy.Batch(), CAP));
            })
            .expectSubscription()
            .thenAwait(Duration.ofSeconds(1 + 2 + 4))
            .expectNext("ok")
            .verifyComplete();
        Assertions.assertEquals(4, attempts.get());
    }

    @Test
    void neverGivesUpOnAFailureThatKeepsComing() {
        var attempts = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> failing(1_000, failed(503), attempts)
                .retryWhen(new BulkRetryPolicy(new RetryBudget(), () -> 0.0)
                    .retryFor(new BulkRetryPolicy.Batch(), CAP)))
            .expectSubscription()
            .thenAwait(Duration.ofHours(2))
            .thenCancel()
            .verify();
        // Zero waits drain the budget: 50 retries, then about one per 10 s as tokens trickle back over 2 hours.
        Assertions.assertTrue(attempts.get() > 700, "attempts " + attempts.get());
    }

    @ParameterizedTest
    @CsvSource({"401", "403"})
    void passesARefusedCredentialOnWithoutRetrying(int status) {
        var attempts = new AtomicInteger();
        StepVerifier.create(failing(100, failed(status), attempts)
                .retryWhen(new BulkRetryPolicy(new RetryBudget()).retryFor(new BulkRetryPolicy.Batch(), CAP)))
            .expectErrorSatisfies(e -> Assertions.assertInstanceOf(OpenSearchClient.OperationFailed.class, e))
            .verify(Duration.ofSeconds(5));
        Assertions.assertEquals(1, attempts.get());
    }

    @Test
    void honorsRetryAfterClampedToTheMaxBackoff() {
        var attempts = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> failing(2, failed(429, Map.of("Retry-After", "7"), "{}"), attempts)
                .retryWhen(new BulkRetryPolicy(new RetryBudget(), () -> 0.0)
                    .retryFor(new BulkRetryPolicy.Batch(), Duration.ofSeconds(5))))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(4_999))
            .then(() -> Assertions.assertEquals(1, attempts.get()))
            .thenAwait(Duration.ofMillis(1))
            .then(() -> Assertions.assertEquals(2, attempts.get()))
            .thenAwait(Duration.ofSeconds(5))
            .expectNext("ok")
            .verifyComplete();
    }

    @Test
    void anEmptyBudgetDelaysRetriesWithoutFailingThem() {
        var attempts = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> {
                var budget = new RetryBudget(BulkErrorClass.TRANSIENT.cost());
                return failing(2, failed(500), attempts)
                    .retryWhen(new BulkRetryPolicy(budget, () -> 0.0)
                        .retryFor(new BulkRetryPolicy.Batch(), CAP));
            })
            .expectSubscription()
            // The first retry spends the whole budget; the second waits for it to refill at a token a second.
            .then(() -> Assertions.assertEquals(2, attempts.get()))
            .thenAwait(Duration.ofSeconds(9))
            .then(() -> Assertions.assertEquals(2, attempts.get()))
            .thenAwait(Duration.ofSeconds(1))
            .expectNext("ok")
            .verifyComplete();
        Assertions.assertEquals(3, attempts.get());
    }

    @Test
    void cancellingAWaitingRetryGivesNoTokensToIt() {
        StepVerifier.withVirtualTime(() -> {
                var budget = new RetryBudget(10);
                return Mono.defer(() -> {
                    budget.acquire(10).subscribe();   // empties the budget
                    var waiting = budget.acquire(5).subscribe();
                    Assertions.assertEquals(1, budget.waiters());
                    waiting.dispose();
                    Assertions.assertEquals(0, budget.waiters());
                    return Mono.just(budget);
                });
            })
            .assertNext(budget -> Assertions.assertEquals(0, budget.tokens()))
            .thenAwait(Duration.ofSeconds(5))
            .verifyComplete();
    }

    @Test
    void successesRefundTheirRetryOrAddATokenAndTokensTrickleBack() {
        StepVerifier.withVirtualTime(() -> Mono.just(new RetryBudget(100)))
            .assertNext(budget -> {
                budget.acquire(10).block();
                budget.acquire(5).block();
                Assertions.assertEquals(85, budget.tokens());
                budget.onSuccess(5);
                Assertions.assertEquals(90, budget.tokens());
                budget.onSuccess(0);
                Assertions.assertEquals(91, budget.tokens());
            })
            .verifyComplete();
    }

    @Test
    void classifiesFailures() {
        Assertions.assertEquals(BulkErrorClass.THROTTLE, BulkErrorClass.of(failed(429)));
        // The target can't take writes: a 503, or blocked writes, whatever the status says.
        Assertions.assertEquals(BulkErrorClass.UNAVAILABLE, BulkErrorClass.of(failed(503)));
        var blocked = "{\"error\":{\"type\":\"cluster_block_exception\",\"reason\":\"index read-only\"}}";
        Assertions.assertEquals(BulkErrorClass.UNAVAILABLE, BulkErrorClass.of(failed(403, Map.of(), blocked)));
        Assertions.assertEquals(BulkErrorClass.UNAVAILABLE, BulkErrorClass.of(failed(429, Map.of(), blocked)));
        Assertions.assertEquals(BulkErrorClass.TRANSIENT, BulkErrorClass.of(failed(500)));
        Assertions.assertEquals(BulkErrorClass.TRANSIENT, BulkErrorClass.of(failed(408)));
        Assertions.assertEquals(BulkErrorClass.AUTH, BulkErrorClass.of(failed(403)));
        Assertions.assertEquals(BulkErrorClass.AUTH, BulkErrorClass.of(failed(401)));
        // Other 4xx statuses are unknown, not permanent: retried, and counted only against a single document.
        Assertions.assertEquals(BulkErrorClass.TRANSIENT, BulkErrorClass.of(failed(404)));
        // A connection that failed or was reset says nothing about a document; a response timeout might.
        Assertions.assertEquals(BulkErrorClass.UNAVAILABLE,
            BulkErrorClass.of(new OpenSearchClient.NoResponseException(new java.io.IOException("reset"))));
        Assertions.assertEquals(BulkErrorClass.TRANSIENT, BulkErrorClass.of(
            new OpenSearchClient.NoResponseException(new java.util.concurrent.TimeoutException("timed out"))));
        Assertions.assertEquals(BulkErrorClass.TRANSIENT, BulkErrorClass.of(new IllegalStateException()));
        var rejected = BulkRequestGenerator.bulkItemResponse(true,
            List.of(BulkRequestGenerator.itemEntry("a"), BulkRequestGenerator.itemEntryRetryableFailure("b")));
        Assertions.assertEquals(BulkErrorClass.THROTTLE, BulkErrorClass.of(failed(200, Map.of(), rejected)));
        var circuitBroken = rejected.replace("es_rejected_execution_exception", "circuit_breaking_exception");
        Assertions.assertEquals(BulkErrorClass.THROTTLE, BulkErrorClass.of(failed(200, Map.of(), circuitBroken)));
        var unavailableItem = rejected.replace("es_rejected_execution_exception", "unavailable_shards_exception");
        Assertions.assertEquals(BulkErrorClass.UNAVAILABLE, BulkErrorClass.of(failed(200, Map.of(), unavailableItem)));
        var unknownItem = rejected.replace("es_rejected_execution_exception", "illegal_state_exception");
        Assertions.assertEquals(BulkErrorClass.TRANSIENT, BulkErrorClass.of(failed(200, Map.of(), unknownItem)));
        // Wrapped, as it arrives after a retry gives up.
        Assertions.assertEquals(BulkErrorClass.THROTTLE, BulkErrorClass.of(new RuntimeException(failed(429))));
    }

    @Test
    void readsRetryAfterAsSecondsOrAnHttpDate() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        Assertions.assertEquals(Duration.ofSeconds(12),
            BulkErrorClass.retryAfter(failed(429, Map.of("retry-after", "12"), ""), now).orElseThrow());
        var date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(30).atZone(ZoneOffset.UTC));
        Assertions.assertEquals(Duration.ofSeconds(30),
            BulkErrorClass.retryAfter(failed(503, Map.of("Retry-After", date), ""), now).orElseThrow());
        Assertions.assertTrue(BulkErrorClass.retryAfter(failed(429, Map.of("Retry-After", "soon"), ""), now).isEmpty());
        Assertions.assertTrue(BulkErrorClass.retryAfter(failed(429), now).isEmpty());
    }
}
