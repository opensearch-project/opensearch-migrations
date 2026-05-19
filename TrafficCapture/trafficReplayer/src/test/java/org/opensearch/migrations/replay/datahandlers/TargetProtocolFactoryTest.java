package org.opensearch.migrations.replay.datahandlers;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * RFC 0001 T6.1 — coverage for {@link TargetProtocolFactory} dispatch + ALPN cache.
 *
 * <p>The current implementation is a skeleton (always returns the H1 consumer). These tests
 * lock in the ALPN-cache contract so the future T6.2 wiring can drop in.
 */
class TargetProtocolFactoryTest {

    private TargetProtocolFactory.ConsumerFactory recordingFactory(AtomicReference<Boolean> calledRef) {
        return (session, ctx, timeout) -> {
            calledRef.set(true);
            // No-op consumer; we don't need a real one for the dispatch test.
            return new IPacketFinalizingConsumer<AggregatedRawResponse>() {
                @Override
                public org.opensearch.migrations.utils.TrackedFuture<String, Void> consumeBytes(
                        io.netty.buffer.ByteBuf packetData) {
                    return org.opensearch.migrations.utils.TrackedFuture.Factory.completedFuture(null, () -> "noop");
                }
                @Override
                public org.opensearch.migrations.utils.TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                    return org.opensearch.migrations.utils.TrackedFuture.Factory.completedFuture(
                            new AggregatedRawResponse(null, 200, Duration.ZERO, java.util.List.of(), null), () -> "noop");
                }
            };
        };
    }

    @Test
    void disabledHttp2_alwaysReturnsH1Consumer() {
        var called = new AtomicReference<>(false);
        var factory = new TargetProtocolFactory(/*targetEnableHttp2*/ false, recordingFactory(called));
        var c = factory.create(URI.create("https://target.example:9200"), null, null, Duration.ZERO);
        Assertions.assertNotNull(c);
        Assertions.assertTrue(called.get(),
                "factory must instantiate the H1 consumer when --targetEnableHttp2 is off");
    }

    @Test
    void enabledHttp2_butCachedAlpnH1_returnsH1Consumer() {
        var called = new AtomicReference<>(false);
        var factory = new TargetProtocolFactory(/*targetEnableHttp2*/ true, recordingFactory(called));
        factory.setCachedAlpnForTesting("target.example:9200", "http/1.1");
        var c = factory.create(URI.create("https://target.example:9200"), null, null, Duration.ZERO);
        Assertions.assertNotNull(c);
        Assertions.assertTrue(called.get());
    }

    @Test
    void enabledHttp2_cachedAlpnH2_currentlyFallsThroughToH1() {
        var called = new AtomicReference<>(false);
        var factory = new TargetProtocolFactory(/*targetEnableHttp2*/ true, recordingFactory(called));
        factory.setCachedAlpnForTesting("target.example:9200", "h2");
        var c = factory.create(URI.create("https://target.example:9200"), null, null, Duration.ZERO);
        Assertions.assertNotNull(c, "fallback to H1 consumer (T6.2 not yet implemented)");
        Assertions.assertTrue(called.get());
    }

    @Test
    void forcedProtocol_overridesProbe() {
        var called = new AtomicReference<>(false);
        var factory = new TargetProtocolFactory(true, recordingFactory(called), /*forcedProtocol*/ "h2");
        var c = factory.create(URI.create("https://target.example:9200"), null, null, Duration.ZERO);
        Assertions.assertNotNull(c);
    }

    @Test
    void alpnCache_persistsAcrossCalls() {
        var probeCount = new AtomicReference<>(0);
        var factory = new TargetProtocolFactory(true, recordingFactory(new AtomicReference<>())) {
            @Override
            protected String probeAlpn(String authority) {
                probeCount.set(probeCount.get() + 1);
                return "h2";
            }
        };
        factory.create(URI.create("https://t1.example:9200"), null, null, Duration.ZERO);
        factory.create(URI.create("https://t1.example:9200"), null, null, Duration.ZERO);
        factory.create(URI.create("https://t1.example:9200"), null, null, Duration.ZERO);
        Assertions.assertEquals(1, probeCount.get(),
                "probeAlpn must be called exactly once per target authority (cached)");
        factory.create(URI.create("https://t2.example:9200"), null, null, Duration.ZERO);
        Assertions.assertEquals(2, probeCount.get(),
                "different authority should trigger a fresh probe");
    }
}
