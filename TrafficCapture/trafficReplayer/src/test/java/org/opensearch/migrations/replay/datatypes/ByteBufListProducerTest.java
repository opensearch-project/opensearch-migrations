package org.opensearch.migrations.replay.datatypes;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class ByteBufListProducerTest {
    @Test
    void attemptOwnsAnIndependentRetainedPacketList() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var producer = ByteBufListProducer.of(packets);

        var attempt = producer.newAttempt();
        Assertions.assertNotSame(packets, attempt.packets());
        attempt.close();
        attempt.close();

        Assertions.assertTrue(attempt.packets().isClosed());
        Assertions.assertFalse(packets.isClosed());
        producer.close();
        Assertions.assertTrue(packets.isClosed());
    }

    @Test
    void diagnosticSnapshotSurvivesProducerRelease() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var producer = ByteBufListProducer.of(packets);
        var snapshot = producer.retainDiagnosticCopy();

        producer.close();

        var composite = snapshot.packets().asCompositeByteBufRetained();
        Assertions.assertEquals("request", composite.toString(StandardCharsets.UTF_8));
        composite.release();
        snapshot.close();
        snapshot.close();
        Assertions.assertTrue(snapshot.isClosed());
    }

    @Test
    void ownershipMetricsReturnToBaselineAndRecordDuplicateCloses() {
        var metrics = new RecordingOwnershipMetrics();
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var producer = ByteBufListProducer.of(packets);
        producer.trackOwnership(metrics);
        var attempt = producer.newAttempt();
        var diagnostic = producer.retainDiagnosticCopy();

        Assertions.assertEquals(1, metrics.handles(ResourceOwnership.Type.PREPARED_REQUEST));
        Assertions.assertEquals(1, metrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(1, metrics.handles(ResourceOwnership.Type.DIAGNOSTIC_PAYLOAD));
        Assertions.assertEquals(7, metrics.bytes(ResourceOwnership.Type.PREPARED_REQUEST));
        Assertions.assertEquals(7, metrics.bytes(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(7, metrics.bytes(ResourceOwnership.Type.DIAGNOSTIC_PAYLOAD));

        attempt.close();
        attempt.close();
        diagnostic.close();
        diagnostic.close();
        producer.close();
        producer.close();

        for (var type : ResourceOwnership.Type.values()) {
            Assertions.assertEquals(0, metrics.handles(type));
            Assertions.assertEquals(0, metrics.buffers(type));
            Assertions.assertEquals(0, metrics.bytes(type));
            Assertions.assertEquals(1, metrics.duplicateCloses(type));
        }
    }

    @Test
    void sharedOwnershipFailureDoesNotPreventALaterSuccessfulClose() {
        var metrics = new RecordingOwnershipMetrics();
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var producer = ByteBufListProducer.of(packets);
        producer.trackOwnership(metrics);
        producer.retain();

        Assertions.assertThrows(IllegalStateException.class, producer::close);
        Assertions.assertEquals(2, producer.refCnt());
        Assertions.assertFalse(packets.isClosed());
        Assertions.assertEquals(1, metrics.invariantFailures(ResourceOwnership.Type.PREPARED_REQUEST));
        Assertions.assertEquals(1, metrics.handles(ResourceOwnership.Type.PREPARED_REQUEST));

        producer.release();
        producer.close();

        Assertions.assertEquals(0, producer.refCnt());
        Assertions.assertTrue(packets.isClosed());
        Assertions.assertEquals(0, metrics.handles(ResourceOwnership.Type.PREPARED_REQUEST));
        Assertions.assertEquals(0, metrics.duplicateCloses(ResourceOwnership.Type.PREPARED_REQUEST));
    }

    @Test
    void failedReleaseKeepsOwnershipOpenUntilAReleaseSucceeds() {
        var metrics = new RecordingOwnershipMetrics();
        var tracker = new ResourceOwnership.Tracker(
            metrics,
            ResourceOwnership.Type.ATTEMPT_PAYLOAD,
            2,
            7
        );

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> tracker.close(() -> {
                throw new IllegalStateException("release failed");
            })
        );

        Assertions.assertEquals(1, metrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(2, metrics.buffers(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(7, metrics.bytes(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(1, metrics.invariantFailures(ResourceOwnership.Type.ATTEMPT_PAYLOAD));

        Assertions.assertTrue(tracker.close(() -> {}));
        Assertions.assertEquals(0, metrics.handles(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(0, metrics.buffers(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
        Assertions.assertEquals(0, metrics.bytes(ResourceOwnership.Type.ATTEMPT_PAYLOAD));
    }

    @Test
    void telemetryFailureCannotCauseAResourceToBeReleasedTwice() {
        var releases = new AtomicInteger();
        var metrics = new ResourceOwnership.Metrics() {
            @Override
            public void ownershipChanged(
                ResourceOwnership.Type type,
                int handleDelta,
                int bufferDelta,
                long byteDelta
            ) {
                if (handleDelta < 0) {
                    throw new AssertionError("closing metric failed");
                }
            }

            @Override
            public void duplicateClose(ResourceOwnership.Type type) {
                throw new AssertionError("duplicate metric failed");
            }
        };
        var tracker = new ResourceOwnership.Tracker(
            metrics,
            ResourceOwnership.Type.ATTEMPT_PAYLOAD,
            1,
            7
        );

        Assertions.assertTrue(tracker.close(releases::incrementAndGet));
        Assertions.assertFalse(tracker.close(releases::incrementAndGet));
        Assertions.assertEquals(1, releases.get());
    }

    private static final class RecordingOwnershipMetrics implements ResourceOwnership.Metrics {
        private final EnumMap<ResourceOwnership.Type, Integer> handles =
            new EnumMap<>(ResourceOwnership.Type.class);
        private final EnumMap<ResourceOwnership.Type, Integer> buffers =
            new EnumMap<>(ResourceOwnership.Type.class);
        private final EnumMap<ResourceOwnership.Type, Long> bytes =
            new EnumMap<>(ResourceOwnership.Type.class);
        private final EnumMap<ResourceOwnership.Type, Integer> duplicateCloses =
            new EnumMap<>(ResourceOwnership.Type.class);
        private final EnumMap<ResourceOwnership.Type, Integer> invariantFailures =
            new EnumMap<>(ResourceOwnership.Type.class);

        @Override
        public void ownershipChanged(
            ResourceOwnership.Type type,
            int handleDelta,
            int bufferDelta,
            long byteDelta
        ) {
            handles.merge(type, handleDelta, Integer::sum);
            buffers.merge(type, bufferDelta, Integer::sum);
            bytes.merge(type, byteDelta, Long::sum);
        }

        @Override
        public void duplicateClose(ResourceOwnership.Type type) {
            duplicateCloses.merge(type, 1, Integer::sum);
        }

        @Override
        public void invariantFailure(ResourceOwnership.Type type) {
            invariantFailures.merge(type, 1, Integer::sum);
        }

        private int handles(ResourceOwnership.Type type) {
            return handles.getOrDefault(type, 0);
        }

        private int buffers(ResourceOwnership.Type type) {
            return buffers.getOrDefault(type, 0);
        }

        private long bytes(ResourceOwnership.Type type) {
            return bytes.getOrDefault(type, 0L);
        }

        private int duplicateCloses(ResourceOwnership.Type type) {
            return duplicateCloses.getOrDefault(type, 0);
        }

        private int invariantFailures(ResourceOwnership.Type type) {
            return invariantFailures.getOrDefault(type, 0);
        }
    }
}
