package org.opensearch.migrations.replay.kafka;


import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that connection contexts are scoped to complete process-local lifetimes.
 */
public class PartitionRevocationStaleStateTest {
    /**
     * A later generation for the same captured connection is a distinct lifetime and may coexist
     * while the prior owner finishes.
     */
    @Test
    void generationBumpCreatesASeparateContextWithoutCrossRouting() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var manager = new ChannelContextManager(
                new RootReplayerContext(telemetry.openTelemetrySdk)
            );
            var generationOne = connectionId(1, 0);
            var generationTwo = connectionId(2, 0);
            var first = manager.retainOrCreateContext(generationOne);
            var second = manager.retainOrCreateContext(generationTwo);

            Assertions.assertNotSame(first, second);
            Assertions.assertEquals(generationOne, first.getConnectionProcessingId());
            Assertions.assertEquals(generationTwo, second.getConnectionProcessingId());

            manager.releaseContextFor(first);
            var secondRetainedAgain = manager.retainOrCreateContext(generationTwo);
            Assertions.assertSame(second, secondRetainedAgain);
            manager.releaseContextFor(second);
            manager.releaseContextFor(secondRetainedAgain);
        }
    }

    /**
     * A fresh process-local lifetime in the same partition generation must not reuse the prior
     * context.
     */
    @Test
    void freshLifetimeWithinOneGenerationUsesASeparateContext() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var manager = new ChannelContextManager(
                new RootReplayerContext(telemetry.openTelemetrySdk)
            );
            var firstLifetime = connectionId(4, 0);
            var secondLifetime = connectionId(4, 1);
            var first = manager.retainOrCreateContext(firstLifetime);
            var second = manager.retainOrCreateContext(secondLifetime);

            Assertions.assertNotSame(first, second);
            manager.releaseContextFor(first);
            manager.releaseContextFor(second);
        }
    }

    private static ConnectionProcessingId connectionId(long generation, long lifetime) {
        return new ConnectionProcessingId(
            new PartitionGenerationId(new TopicPartition("topic", 1), generation),
            new CapturedConnectionId("writer", "connection"),
            lifetime
        );
    }

}
