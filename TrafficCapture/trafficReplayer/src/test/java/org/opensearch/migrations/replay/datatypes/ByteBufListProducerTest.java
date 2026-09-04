package org.opensearch.migrations.replay.datatypes;

import java.nio.charset.StandardCharsets;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class ByteBufListProducerTest {
    @Test
    void borrowedAttemptLeavesStoredPacketsOwnedByProducer() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var producer = ByteBufListProducer.of(packets);

        var attempt = producer.newAttempt();
        Assertions.assertSame(packets, attempt.packets());
        attempt.close();
        attempt.close();

        Assertions.assertFalse(packets.isClosed());
        producer.release();
        Assertions.assertTrue(packets.isClosed());
    }

    @Test
    void diagnosticSnapshotSurvivesProducerRelease() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var producer = ByteBufListProducer.of(packets);
        var snapshot = producer.diagnosticSnapshot();

        producer.release();

        var composite = snapshot.asCompositeByteBufRetained();
        Assertions.assertEquals("request", composite.toString(StandardCharsets.UTF_8));
        composite.release();
        snapshot.release();
    }
}
