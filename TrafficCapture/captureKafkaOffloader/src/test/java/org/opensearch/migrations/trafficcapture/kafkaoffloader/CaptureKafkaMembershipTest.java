package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureKafkaMembershipTest {
    private static final String TOPIC = "traffic";
    private static final String ACTIVATION_ID = "activation";

    @Test
    void replacementAssignmentChangesOnlyNewConnectionRouting() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var publisher = new InMemoryAssignmentPublisher(routingState);
        var membership = membership(routingState, consumer, publisher);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);
        var partition2 = new TopicPartition(TOPIC, 2);
        consumer.assign(List.of(partition0, partition1));

        membership.onPartitionsAssigned(List.of(partition0, partition1));
        var existingConnection = routingState.routeNewConnection("existing");
        var existingWriter = existingConnection.writerNodeId();
        var existingPartition = existingConnection.partition();

        membership.onPartitionsRevoked(List.of(partition0, partition1));
        consumer.assign(List.of(partition2));
        membership.onPartitionsAssigned(List.of(partition2));
        var newConnection = routingState.routeNewConnection("new");

        assertEquals("activation:1", existingWriter);
        assertTrue(Set.of(0, 1).contains(existingPartition));
        assertEquals(existingWriter, existingConnection.writerNodeId());
        assertEquals(existingPartition, existingConnection.partition());
        assertEquals("activation:2", newConnection.writerNodeId());
        assertEquals(2, newConnection.partition());
        assertEquals(List.of(List.of(0, 1), List.of(2)), publisher.installedAssignments());
    }

    @Test
    void emptyAssignmentAfterRevocationKeepsLastUsableAssignment() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 2);
        var publisher = new InMemoryAssignmentPublisher(routingState);
        var membership = membership(routingState, consumer, publisher);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);
        consumer.assign(List.of(partition0, partition1));

        membership.onPartitionsAssigned(List.of(partition0, partition1));
        membership.onPartitionsRevoked(List.of(partition0, partition1));
        consumer.assign(List.of());
        membership.onPartitionsAssigned(List.of());

        var routed = routingState.routeNewConnection("after-empty-assignment");
        assertEquals("activation:1", routed.writerNodeId());
        assertTrue(Set.of(0, 1).contains(routed.partition()));
        assertEquals(List.of(0, 1), routingState.assignedPartitions());
        assertEquals(List.of(List.of(0, 1)), publisher.installedAssignments());
    }

    @Test
    void lostPartitionsKeepLastUsableAssignmentUntilKafkaSuppliesAReplacement() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var publisher = new InMemoryAssignmentPublisher(routingState);
        var membership = membership(routingState, consumer, publisher);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);
        var partition2 = new TopicPartition(TOPIC, 2);
        consumer.assign(List.of(partition0, partition1));

        membership.onPartitionsAssigned(List.of(partition0, partition1));
        membership.onPartitionsLost(List.of(partition0, partition1));

        var whileLost = routingState.routeNewConnection("while-lost");
        assertEquals("activation:1", whileLost.writerNodeId());
        assertTrue(Set.of(0, 1).contains(whileLost.partition()));

        consumer.assign(List.of(partition2));
        membership.onPartitionsAssigned(List.of(partition2));

        var afterReplacement = routingState.routeNewConnection("after-replacement");
        assertEquals("activation:2", afterReplacement.writerNodeId());
        assertEquals(2, afterReplacement.partition());
    }

    @Test
    void pollFailureAfterInitialAssignmentKeepsLastUsableAssignment() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = new InMemoryAssignmentPublisher(routingState);
        var membershipFailure = new AtomicReference<Throwable>();
        var initialAssignment = new CountDownLatch(1);
        var partition = new TopicPartition(TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.schedulePollTask(() -> consumer.rebalance(List.of(partition)));
        consumer.schedulePollTask(() -> {
            throw new IllegalStateException("membership failed");
        });
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            initialAssignment::countDown,
            membershipFailure::set,
            ignored -> {}
        );

        membership.start();

        assertTrue(initialAssignment.await(1, TimeUnit.SECONDS));
        awaitConsumerClose(consumer);
        assertEquals(0, routingState.routeNewConnection("after-poll-failure").partition());
        assertNull(membershipFailure.get());
        assertNull(publisher.failure());
        membership.close();
    }

    @Test
    void retriablePollFailureKeepsLastAssignmentAndAcceptsAReplacement() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 2);
        var publisher = new InMemoryAssignmentPublisher(routingState);
        var membershipFailure = new AtomicReference<Throwable>();
        var initialAssignment = new CountDownLatch(1);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);
        consumer.updateBeginningOffsets(Map.of(partition0, 0L, partition1, 0L));
        consumer.schedulePollTask(() -> consumer.rebalance(List.of(partition0)));
        consumer.schedulePollTask(() -> {
            throw new TimeoutException("coordinator temporarily unavailable");
        });
        consumer.schedulePollTask(() -> consumer.rebalance(List.of(partition1)));
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            initialAssignment::countDown,
            membershipFailure::set,
            ignored -> {}
        );

        membership.start();

        assertTrue(initialAssignment.await(1, TimeUnit.SECONDS));
        awaitWriter(routingState, "activation:2");
        assertEquals(List.of(1), routingState.assignedPartitions());
        assertEquals(1, routingState.routeNewConnection("after-retry").partition());
        assertNull(membershipFailure.get());
        assertNull(publisher.failure());
        membership.close();
    }

    @Test
    void pollFailureBeforeAnyUsableAssignmentReportsStartupFailure() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var membershipFailure = new AtomicReference<Throwable>();
        var failureReported = new CountDownLatch(1);
        consumer.schedulePollTask(() -> {
            throw new IllegalStateException("membership failed before assignment");
        });
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            new InMemoryAssignmentPublisher(routingState),
            () -> {},
            failure -> {
                membershipFailure.set(failure);
                failureReported.countDown();
            },
            ignored -> {}
        );

        membership.start();

        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertEquals("membership failed before assignment", membershipFailure.get().getMessage());
        assertThrows(
            IllegalStateException.class,
            () -> routingState.routeNewConnection("without-an-assignment")
        );
        membership.close();
    }

    @Test
    void closeBeforeStartClosesTheConsumer() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var membership = membership(
            routingState,
            consumer,
            new InMemoryAssignmentPublisher(routingState)
        );

        membership.close();

        assertTrue(consumer.closed());
    }

    private static CaptureKafkaMembership membership(
        CaptureRoutingState routingState,
        MockConsumer<String, byte[]> consumer,
        CaptureAssignmentPublisher publisher
    ) {
        return new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            () -> {},
            ignored -> {},
            ignored -> {}
        );
    }

    private static void awaitConsumerClose(MockConsumer<String, byte[]> consumer)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!consumer.closed() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(consumer.closed());
    }

    private static void awaitWriter(CaptureRoutingState routingState, String expectedWriter)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!expectedWriter.equals(routingState.currentWriterNodeId())
            && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expectedWriter, routingState.currentWriterNodeId());
    }

    private static final class InMemoryAssignmentPublisher implements CaptureAssignmentPublisher {
        private static final Duration EXPIRATION_INTERVAL = Duration.ofSeconds(30);

        private final CaptureRoutingState routingState;
        private final List<List<Integer>> installedAssignments = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private long brokerTime = 1_000;

        private InMemoryAssignmentPublisher(CaptureRoutingState routingState) {
            this.routingState = routingState;
        }

        @Override
        public CompletableFuture<String> installAssignment(Collection<Integer> partitions) {
            var assignmentSnapshot = List.copyOf(partitions);
            installedAssignments.add(assignmentSnapshot);
            var assignment = routingState.prepareAssignment(assignmentSnapshot);
            for (var writerPartition : assignment.writerPartitions()) {
                routingState.acceptHeartbeatLogAppendTime(
                    writerPartition,
                    brokerTime++,
                    EXPIRATION_INTERVAL
                );
            }
            routingState.activateAssignment(assignment);
            return CompletableFuture.completedFuture(assignment.writerNodeId());
        }

        @Override
        public void stopAfterFailure(Throwable failure) {
            this.failure.compareAndSet(null, failure);
        }

        private List<List<Integer>> installedAssignments() {
            return List.copyOf(installedAssignments);
        }

        private Throwable failure() {
            return failure.get();
        }
    }
}
