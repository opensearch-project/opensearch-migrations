package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IOrderlyRetirableCaptureFactory;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureProxyOrderlyShutdownTest {
    @Test
    void plannedShutdownClosesCaptureAdmissionThenStopsConnectionsAndWaitsBeforeClosing()
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
                    Duration.ofSeconds(5),
                    () -> events.add("warning")
                );
                return null;
            });

            assertTrue(captureFactory.retirementStarted.await(1, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone());
            assertEquals(List.of("retire", "stop"), List.copyOf(events));

            captureFactory.retirement.complete(null);
            shutdown.get(1, TimeUnit.SECONDS);

            assertEquals(List.of("retire", "stop", "close"), List.copyOf(events));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingFiveMinuteTargetWarnsButDoesNotStopRetirement() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var warning = new CountDownLatch(1);
        var proxy = new RecordingProxy(events);
        var captureFactory = new RecordingCaptureFactory(events);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var shutdown = executor.submit(() -> {
                CaptureProxy.performOrderlyShutdown(
                    proxy,
                    captureFactory,
                    Duration.ofMillis(10),
                    () -> {
                        events.add("warning");
                        warning.countDown();
                    }
                );
                return null;
            });

            assertTrue(warning.await(1, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone());
            assertEquals(List.of("retire", "stop", "warning"), List.copyOf(events));

            captureFactory.retirement.complete(null);
            shutdown.get(1, TimeUnit.SECONDS);

            assertEquals(
                List.of("retire", "stop", "warning", "close"),
                List.copyOf(events)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private static class RecordingProxy extends NettyScanningHttpProxy {
        private final List<String> events;

        private RecordingProxy(List<String> events) {
            super(0, ignored -> {});
            this.events = events;
        }

        @Override
        public void stop() {
            events.add("stop");
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
            events.add("retire");
            retirementStarted.countDown();
            return retirement;
        }

        @Override
        public void close() {
            events.add("close");
        }
    }
}
