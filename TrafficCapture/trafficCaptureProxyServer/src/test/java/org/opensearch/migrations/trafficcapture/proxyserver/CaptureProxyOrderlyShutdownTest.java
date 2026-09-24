package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IOrderlyRetirableCaptureFactory;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureProxyOrderlyShutdownTest {
    @Test
    void plannedShutdownClosesListenerThenRetiresCaptureBeforeCleanup()
        throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var proxy = new RecordingProxy(events);
        var captureFactory = new RecordingCaptureFactory(events);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var shutdown = executor.submit(() -> {
                CaptureProxy.performOrderlyShutdown(
                    proxy,
                    captureFactory,
                    Duration.ofMillis(100),
                    Duration.ofMillis(200),
                    Duration.ofMillis(300),
                    () -> events.add("diagnostics")
                );
                return null;
            });

            assertTrue(captureFactory.retirementStarted.await(1, TimeUnit.SECONDS));
            assertTrue(proxy.connectionDrainRequested.await(1, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone());
            assertIterableEquals(
                List.of("close-listener", "start-retirement", "await-connection-drain"),
                List.copyOf(events)
            );

            proxy.connectionsDrained.complete(null);
            captureFactory.retirement.complete(null);
            shutdown.get(1, TimeUnit.SECONDS);

            assertIterableEquals(
                List.of(
                    "close-listener",
                    "start-retirement",
                    "await-connection-drain",
                    "close-capture-factory",
                    "stop-event-loops"
                ),
                List.copyOf(events)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void naturalDrainForcesConnectionCloseAndRetirementDeadlineEmitsDiagnostics() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var diagnostics = new CountDownLatch(1);
        var proxy = new RecordingProxy(events);
        var captureFactory = new RecordingCaptureFactory(events);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var shutdown = executor.submit(() ->
                CaptureProxy.performOrderlyShutdown(
                    proxy,
                    captureFactory,
                    Duration.ofMillis(10),
                    Duration.ofMillis(40),
                    Duration.ofMillis(100),
                    () -> {
                        events.add("diagnostics");
                        diagnostics.countDown();
                    }
                )
            );

            assertTrue(diagnostics.await(1, TimeUnit.SECONDS));
            shutdown.get(1, TimeUnit.SECONDS);

            assertIterableEquals(
                List.of(
                    "close-listener",
                    "start-retirement",
                    "await-connection-drain",
                    "count-active-connections",
                    "force-close-connections",
                    "diagnostics",
                    "close-capture-factory",
                    "stop-event-loops"
                ),
                List.copyOf(events)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownCoreReturnsByHookCompletionBoundaryWhenCleanupCannotFinish() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var proxy = new RecordingProxy(events);
        proxy.connectionsDrained.complete(null);
        proxy.blockEventLoopShutdown.set(true);
        var captureFactory = new RecordingCaptureFactory(events);
        captureFactory.retirement.complete(null);
        try {
            var completed = CaptureProxy.performOrderlyShutdown(
                proxy,
                captureFactory,
                Duration.ofMillis(5),
                Duration.ofMillis(15),
                Duration.ofMillis(30),
                () -> events.add("diagnostics")
            );

            assertFalse(completed);
            assertIterableEquals(
                List.of(
                    "close-listener",
                    "start-retirement",
                    "await-connection-drain",
                    "close-capture-factory",
                    "stop-event-loops"
                ),
                List.copyOf(events)
            );
        } finally {
            proxy.releaseEventLoopShutdown.countDown();
        }
    }

    @Test
    void productionShutdownBoundsAreSixtyTwoFortyTwoSeventyAndThreeHundredSeconds() {
        assertEquals(Duration.ofSeconds(60), CaptureProxy.ORDERLY_NATURAL_DRAIN);
        assertEquals(Duration.ofSeconds(240), CaptureProxy.ORDERLY_RETIREMENT_DEADLINE);
        assertEquals(Duration.ofSeconds(270), CaptureProxy.ORDERLY_SHUTDOWN_HOOK_COMPLETION);
        assertEquals(Duration.ofSeconds(300), CaptureProxy.ORDERLY_SHUTDOWN_HARD_STOP);
    }

    @Test
    void shutdownHookUsesIndependentRuntimeHaltWatchdogAndNeverSystemExit() throws IOException {
        var source = Files.readString(Path.of(
            "src/main/java/org/opensearch/migrations/trafficcapture/proxyserver/CaptureProxy.java"
        ));
        var hookStart = source.indexOf("var shutdownHook = new Thread");
        var hookEnd = source.indexOf("Runtime.getRuntime().addShutdownHook(shutdownHook)", hookStart);
        assertTrue(hookStart >= 0 && hookEnd > hookStart);

        var shutdownHook = source.substring(hookStart, hookEnd);
        assertTrue(shutdownHook.contains("\"proxy-orderly-shutdown-watchdog\""));
        assertTrue(shutdownHook.contains("ORDERLY_SHUTDOWN_HARD_STOP.toNanos()"));
        assertTrue(shutdownHook.contains("Runtime.getRuntime().halt("));
        assertFalse(shutdownHook.contains("System.exit("));
    }

    private static class RecordingProxy extends NettyScanningHttpProxy {
        private final List<String> events;
        private final CompletableFuture<Void> connectionsDrained = new CompletableFuture<>();
        private final CountDownLatch connectionDrainRequested = new CountDownLatch(1);
        private final AtomicBoolean blockEventLoopShutdown = new AtomicBoolean();
        private final CountDownLatch releaseEventLoopShutdown = new CountDownLatch(1);

        private RecordingProxy(List<String> events) {
            super(0, ignored -> {});
            this.events = events;
        }

        @Override
        public void stopAcceptingNewConnections() {
            events.add("close-listener");
        }

        @Override
        public CompletableFuture<Void> whenNoActiveConnections() {
            events.add("await-connection-drain");
            connectionDrainRequested.countDown();
            return connectionsDrained;
        }

        @Override
        public CompletableFuture<Void> disconnectActiveConnections() {
            events.add("force-close-connections");
            connectionsDrained.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public int activeConnectionCount() {
            events.add("count-active-connections");
            return connectionsDrained.isDone() ? 0 : 1;
        }

        @Override
        public void stopEventLoops() throws InterruptedException {
            events.add("stop-event-loops");
            if (blockEventLoopShutdown.get()) {
                releaseEventLoopShutdown.await();
            }
        }
    }

    private static class RecordingCaptureFactory implements
        IConnectionCaptureFactory<Void>,
        IOrderlyRetirableCaptureFactory,
        AutoCloseable {
        private final List<String> events;
        private final CountDownLatch retirementStarted = new CountDownLatch(1);
        private final CompletableFuture<Void> retirement = new CompletableFuture<>();

        private RecordingCaptureFactory(List<String> events) {
            this.events = events;
        }

        @Override
        public IChannelConnectionCaptureSerializer<Void> createOffloader(IConnectionContext ctx)
            throws IOException {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public CompletableFuture<Void> retireForOrderlyShutdown() {
            events.add("start-retirement");
            retirementStarted.countDown();
            return retirement;
        }

        @Override
        public void close() {
            events.add("close-capture-factory");
        }
    }
}
