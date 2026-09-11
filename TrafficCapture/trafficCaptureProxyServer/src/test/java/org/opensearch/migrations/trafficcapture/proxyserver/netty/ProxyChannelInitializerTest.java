package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.CaptureFailurePolicy;
import org.opensearch.migrations.trafficcapture.netty.CaptureProcessState;
import org.opensearch.migrations.trafficcapture.netty.IncompleteRequestLimits;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.tracing.RootWireLoggingContext;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyChannelInitializerTest {
    @Test
    void newConnectionsUseUncapturedFactoryAfterProcessEntersPassThrough() throws Exception {
        var instrumentation = new InMemoryInstrumentationBundle(false, false);
        var rootContext = new RootWireLoggingContext(
            instrumentation.openTelemetrySdk,
            IContextTracker.DO_NOTHING_TRACKER
        );
        var captureFactoryCalls = new AtomicInteger();
        var passThroughFactoryCalls = new AtomicInteger();
        var passThroughDelegate = new InMemoryConnectionCaptureFactory(
            "pass-through",
            1024 * 1024,
            () -> {}
        );
        IConnectionCaptureFactory<Void> captureFactory = context -> {
            captureFactoryCalls.incrementAndGet();
            throw new AssertionError("The authoritative capture factory must not be used");
        };
        IConnectionCaptureFactory<Void> passThroughFactory = context -> {
            passThroughFactoryCalls.incrementAndGet();
            return passThroughDelegate.createOffloader(context);
        };
        var processState = new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN);
        processState.requiredCaptureFailed(new IllegalStateException("Kafka capture failed"));
        var initializer = new ProxyChannelInitializer<>(
            rootContext,
            null,
            null,
            captureFactory,
            passThroughFactory,
            new RequestCapturePredicate(),
            IncompleteRequestLimits.DEFAULT,
            Duration.ofHours(1),
            processState
        );

        var channel = new EmbeddedChannel(initializer.createCaptureHandler("connection"));
        channel.finishAndReleaseAll();

        assertEquals(0, captureFactoryCalls.get());
        assertEquals(1, passThroughFactoryCalls.get());
        instrumentation.close();
    }

    @Test
    void newConnectionsAreRejectedAfterFailClosedTerminationBegins() throws Exception {
        var instrumentation = new InMemoryInstrumentationBundle(false, false);
        var rootContext = new RootWireLoggingContext(
            instrumentation.openTelemetrySdk,
            IContextTracker.DO_NOTHING_TRACKER
        );
        var captureFactoryCalls = new AtomicInteger();
        IConnectionCaptureFactory<Void> captureFactory = context -> {
            captureFactoryCalls.incrementAndGet();
            throw new AssertionError("No capture handler may be created while terminating");
        };
        var processState = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
        processState.requiredCaptureFailed(new IllegalStateException("Kafka capture failed"));
        var initializer = new ProxyChannelInitializer<>(
            rootContext,
            null,
            null,
            captureFactory,
            captureFactory,
            new RequestCapturePredicate(),
            IncompleteRequestLimits.DEFAULT,
            Duration.ofHours(1),
            processState
        );

        assertThrows(
            IllegalStateException.class,
            () -> initializer.createCaptureHandler("connection")
        );
        assertEquals(0, captureFactoryCalls.get());
        instrumentation.close();
    }
}
