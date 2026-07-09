package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.trafficcapture.proxyserver.netty.UnauthenticatedClientLogDeduper.KnownEvent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UnauthenticatedClientLogDeduperTest {

    @Test
    void testFirstEventLogsAndRepeatedEventsAreSuppressedUntilWindowExpires() {
        var nowNanos = new AtomicLong(0);
        var deduper = new UnauthenticatedClientLogDeduper(Duration.ofSeconds(1), nowNanos::get);

        var firstDecision = deduper.record(KnownEvent.FRONTSIDE_TLS_HANDSHAKE_FAILURE);
        Assertions.assertTrue(firstDecision.shouldLog());
        Assertions.assertEquals(0, firstDecision.getSuppressedCountSinceLastLog());

        var secondDecision = deduper.record(KnownEvent.FRONTSIDE_TLS_HANDSHAKE_FAILURE);
        var thirdDecision = deduper.record(KnownEvent.FRONTSIDE_TLS_HANDSHAKE_FAILURE);
        Assertions.assertFalse(secondDecision.shouldLog());
        Assertions.assertFalse(thirdDecision.shouldLog());

        nowNanos.set(Duration.ofSeconds(1).toNanos());
        var afterWindowDecision = deduper.record(KnownEvent.FRONTSIDE_TLS_HANDSHAKE_FAILURE);
        Assertions.assertTrue(afterWindowDecision.shouldLog());
        Assertions.assertEquals(2, afterWindowDecision.getSuppressedCountSinceLastLog());
    }

    @Test
    void testDeduperUsesOnlyTheFixedKnownEventSet() {
        var deduper = new UnauthenticatedClientLogDeduper(Duration.ofSeconds(1), () -> 0);
        var knownEventCount = deduper.knownEventCount();

        for (int i = 0; i < 100; i++) {
            deduper.record(KnownEvent.FRONTSIDE_TLS_HANDSHAKE_FAILURE);
        }

        Assertions.assertEquals(KnownEvent.values().length, knownEventCount);
        Assertions.assertEquals(knownEventCount, deduper.knownEventCount());
    }
}
