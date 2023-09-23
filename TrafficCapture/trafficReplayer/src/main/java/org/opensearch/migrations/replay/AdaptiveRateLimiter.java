package org.opensearch.migrations.replay;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.netty.channel.ChannelFuture;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * This class is a placeholder now, but a place for the rest of the codebase to centralize its
 * retry and ratelimiting needs.  The idea is that this class will do actions asynchronously
 * that are limited in time by some ceiling.  That ceiling may also be adjusted based upon
 * retries that need to happen.  Retry intervals themselves may be localized or global for
 * all actions concurrently happening within the system.
 *
 * For now though, this class is just meant as a starting point
 * @param <T>
 */
public class AdaptiveRateLimiter<D,T> {

    public DiagnosticTrackableCompletableFuture<D,T>
    get(Supplier<DiagnosticTrackableCompletableFuture<D,T>> producer) {
        var intervalFunction = IntervalFunction.ofExponentialBackoff(Duration.ofMillis(1),2,Duration.ofSeconds(1));
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(Integer.MAX_VALUE)
                .intervalFunction(intervalFunction)
                .build();
        var rateLimiterConfig = RateLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(1))
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(10)  // adjust this dynamically based on your needs
                .build();

        var retry = Retry.of("Retry_" + System.identityHashCode(producer), retryConfig);
        var rateLimiter = RateLimiter.of("RateLimiter_" + System.identityHashCode(producer), rateLimiterConfig);

        var rateLimitedSupplier =
                RateLimiter.decorateCompletionStage(rateLimiter, () -> {
                    var df = producer.get();
                    return df.future;
                });

        // TODO: I'm still trying to figure out how to connect these together and to round-trip
        // marshal the DTCF through as a CompletionStage

        return producer.get();
    }

}
