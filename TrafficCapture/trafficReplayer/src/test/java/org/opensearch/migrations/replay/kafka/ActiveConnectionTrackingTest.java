package org.opensearch.migrations.replay.kafka;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Preserves the inherited connection-reuse assertions on the G5 connection-context registry.
 */
class ActiveConnectionTrackingTest {
    @Test
    void retainAndReleaseAreAtomicForOneConnectionLifetime() throws Exception {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var manager = new ChannelContextManager(
                new RootReplayerContext(telemetry.openTelemetrySdk)
            );
            var connectionId = connectionId("keep-alive", 1, 0);
            var contexts = ConcurrentHashMap.<IReplayContexts.IConnectionContext>newKeySet();
            var retained = new CountDownLatch(8);
            var release = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(8);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> {
                        var context = manager.retainOrCreateContext(connectionId);
                        contexts.add(context);
                        retained.countDown();
                        release.await();
                        manager.releaseContextFor(context);
                        return null;
                    }));
                }
                Assertions.assertTrue(retained.await(5, TimeUnit.SECONDS));
                Assertions.assertEquals(1, contexts.size());
                release.countDown();
                for (var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            } finally {
                release.countDown();
                executor.shutdownNow();
            }

            var closedContext = contexts.iterator().next();
            var laterContext = manager.retainOrCreateContext(connectionId);
            Assertions.assertNotSame(closedContext, laterContext);
            manager.releaseContextFor(laterContext);

            var metrics = telemetry.getFinishedMetrics();
            assertLongPoint(
                metrics,
                IReplayContexts.MetricNames.ACTIVE_CHANNELS_YET_TO_BE_FULLY_DISCARDED,
                0
            );
        }
    }

    @Test
    void releaseRemovesOnlyTheMatchingConnectionLifetime() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var manager = new ChannelContextManager(
                new RootReplayerContext(telemetry.openTelemetrySdk)
            );
            var firstId = connectionId("first", 2, 0);
            var secondId = connectionId("second", 2, 0);
            var first = manager.retainOrCreateContext(firstId);
            var second = manager.retainOrCreateContext(secondId);

            manager.releaseContextFor(first);
            var retainedSecond = manager.retainOrCreateContext(secondId);
            Assertions.assertSame(second, retainedSecond);
            manager.releaseContextFor(second);
            manager.releaseContextFor(retainedSecond);
        }
    }

    private static ConnectionProcessingId connectionId(
        String connection,
        long generation,
        long lifetime
    ) {
        return new ConnectionProcessingId(
            new PartitionGenerationId(new TopicPartition("topic", 0), generation),
            new CapturedConnectionId("writer", connection),
            lifetime
        );
    }

    private static void assertLongPoint(
        Iterable<MetricData> metrics,
        String name,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var point = metric.getLongSumData()
                    .getPoints()
                    .stream()
                    .filter(candidate -> candidate.getAttributes().equals(Attributes.empty()))
                    .findFirst()
                    .orElseThrow();
                Assertions.assertEquals(expectedValue, point.getValue());
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }
}
