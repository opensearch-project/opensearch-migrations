package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;

import org.opensearch.migrations.tracing.InstrumentationTest;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that pendingTrafficSourceReaderInterruptedCloses uses nodeId-aware session keys
 * so that connections from different source nodes with the same connectionId don't collide.
 */
class KafkaTrafficCaptureSourceCrossNodeTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    @Test
    void onNetworkConnectionClosed_differentNodes_sameConnectionId_independentTracking() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // Register synthetic closes for two different nodes with the same connectionId
            source.pendingTrafficSourceReaderInterruptedCloses.put("node-A.conn-1:0:1", Boolean.TRUE);
            source.pendingTrafficSourceReaderInterruptedCloses.put("node-B.conn-1:0:1", Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(2);

            // Close node-A's connection
            source.onNetworkConnectionClosed("node-A", "conn-1", 0, 1);

            Assertions.assertEquals(1, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Only node-A's synthetic close should be decremented");
            Assertions.assertNull(source.pendingTrafficSourceReaderInterruptedCloses.get("node-A.conn-1:0:1"),
                "node-A's pending close should be removed");
            Assertions.assertNotNull(source.pendingTrafficSourceReaderInterruptedCloses.get("node-B.conn-1:0:1"),
                "node-B's pending close must still be present");

            // Close node-B's connection
            source.onNetworkConnectionClosed("node-B", "conn-1", 0, 1);

            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Both synthetic closes should now be decremented");
        }
    }
}
