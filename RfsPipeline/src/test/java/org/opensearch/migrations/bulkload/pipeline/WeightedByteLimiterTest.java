package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeightedByteLimiterTest {

    @Test
    void waitsUntilEnoughBytesAreReleased() {
        var limiter = new WeightedByteLimiter(10);
        var first = limiter.acquire(7).block(Duration.ofSeconds(1));
        var waiting = limiter.acquire(4).cache();

        StepVerifier.create(waiting)
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(25))
            .then(() -> first.shrinkTo(6))
            .assertNext(permit -> {
                assertEquals(4, permit.bytes());
                permit.release();
            })
            .verifyComplete();

        first.release();
    }

    @Test
    void rejectsARequestLargerThanTheWholePool() {
        var limiter = new WeightedByteLimiter(10);

        StepVerifier.create(limiter.acquire(11))
            .expectErrorMatches(error -> error instanceof IllegalArgumentException
                && error.getMessage().contains("exceeds limiter capacity"))
            .verify();
    }
}
