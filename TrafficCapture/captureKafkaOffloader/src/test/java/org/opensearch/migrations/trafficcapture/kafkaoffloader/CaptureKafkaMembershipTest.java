package org.opensearch.migrations.trafficcapture.kafkaoffloader;

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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureKafkaMembershipTest {
    private static final String TOPIC = "traffic";

    @Test
    void pollThreadPausesAssignmentsAndRevocationKeepsLastCompletedAssignment() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(3, List.of());
        var publisher = publisher();
        var initialAssignment = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            writeGate(),
            assignmentTracker("node-a"),
            1,
            initialAssignment::countDown,
            failure::set,
            failure::set
        );
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);
        consumer.updateBeginningOffsets(Map.of(partition0, 0L, partition1, 0L));
        consumer.schedulePollTask(() -> consumer.rebalance(List.of(partition0, partition1)));

        membership.start();
        assertTrue(initialAssignment.await(1, TimeUnit.SECONDS));

        assertEquals(List.of(0, 1), routingState.assignedPartitions());
        assertEquals(Set.of(partition0, partition1), consumer.paused());
        membership.onPartitionsRevoked(List.of(partition1));
        assertEquals(List.of(0, 1), routingState.assignedPartitions());
        int temporaryPartition = routingState.admitConnection("during-rebalance");
        assertTrue(List.of(0, 1).contains(temporaryPartition));
        routingState.remove("during-rebalance", temporaryPartition);
        verify(publisher, org.mockito.Mockito.never())
            .publishSelfNoMoreWrites(any(CaptureRoutingState.SelfRelease.class));

        membership.onPartitionsAssigned(List.of());

        assertEquals(List.of(0), routingState.assignedPartitions());
        assertEquals(0, routingState.admitConnection("after-completed-rebalance"));
        verify(publisher).publishSelfNoMoreWrites(any(CaptureRoutingState.SelfRelease.class));
        assertEquals(null, failure.get());
        membership.close();
        assertTrue(consumer.closed());
    }

    @Test
    void cooperativeEmptyAssignmentCallbackPreservesRetainedPartitions() {
        var routingState = new CaptureRoutingState(3, List.of());
        var membership = membership(routingState);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);

        membership.onPartitionsAssigned(List.of(partition0, partition1));
        membership.onPartitionsRevoked(List.of(partition1));
        membership.onPartitionsAssigned(List.of());

        assertEquals(List.of(0), routingState.assignedPartitions());
    }

    @Test
    void minimumMemberCountGatesNewConnectionsWithoutAnotherAssignmentPhase() {
        var routingState = new CaptureRoutingState(1, List.of());
        var tracker = assignmentTracker("node-a");
        var initialAssignment = new java.util.concurrent.atomic.AtomicInteger();
        var membership = new CaptureKafkaMembership(
            mock(org.apache.kafka.clients.consumer.Consumer.class),
            TOPIC,
            routingState,
            publisher(),
            writeGate(),
            tracker,
            2,
            initialAssignment::incrementAndGet,
            ignored -> {},
            ignored -> {}
        );
        var partition = new TopicPartition(TOPIC, 0);

        membership.onPartitionsAssigned(List.of(partition));

        assertEquals(0, initialAssignment.get());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> routingState.admitConnection("too-early")
        );

        tracker.replaceMembers(Set.of("node-a", "node-b"));
        membership.onPartitionsAssigned(List.of());

        assertEquals(1, initialAssignment.get());
        assertEquals(0, routingState.admitConnection("accepted"));
    }

    @Test
    void lostPartitionsKeepLastCompletedAssignmentUntilReplacementCompletes() {
        @SuppressWarnings("unchecked")
        var consumer = mock(org.apache.kafka.clients.consumer.Consumer.class);
        var routingState = new CaptureRoutingState(3, List.of(0, 1));
        var publisher = publisher();
        var terminalFailure = new AtomicReference<Throwable>();
        var writeGate = writeGate();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            writeGate,
            assignmentTracker("node-a"),
            1,
            () -> {},
            terminalFailure::set,
            terminalFailure::set
        );

        membership.onPartitionsLost(List.of(new TopicPartition(TOPIC, 1)));

        assertEquals(List.of(0, 1), routingState.assignedPartitions());
        assertTrue(List.of(0, 1).contains(routingState.admitConnection("while-membership-is-changing")));
        assertEquals(null, terminalFailure.get());
        verify(publisher, org.mockito.Mockito.never()).failClosed(any());

        membership.onPartitionsAssigned(List.of(new TopicPartition(TOPIC, 2)));

        assertEquals(List.of(0, 2), routingState.assignedPartitions());
        assertTrue(List.of(0, 2).contains(routingState.admitConnection("after-normal-assignment")));
    }

    @Test
    void surfacedPollFailureAfterInitialAssignmentKeepsAdmissionAndDoesNotReportStartupFailure()
        throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(1, List.of());
        var publisher = publisher();
        var writeGate = writeGate();
        var membershipFailure = new AtomicReference<Throwable>();
        var publisherFailure = new AtomicReference<Throwable>();
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
            writeGate,
            assignmentTracker("node-a"),
            1,
            initialAssignment::countDown,
            membershipFailure::set,
            publisherFailure::set
        );

        membership.start();

        assertTrue(initialAssignment.await(1, TimeUnit.SECONDS));
        long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!consumer.closed() && System.nanoTime() < closeDeadline) {
            Thread.sleep(1);
        }
        assertTrue(consumer.closed());
        membership.close();
        assertEquals(0, routingState.admitConnection("after-membership-failure"));
        assertEquals(null, membershipFailure.get());
        assertEquals(null, publisherFailure.get());
        assertEquals(null, writeGate.failureIfNotWritable());
        verify(publisher, org.mockito.Mockito.never()).failClosed(any());

        var writeFailure = new IllegalStateException("producer failed");
        writeGate.trip(writeFailure);

        assertEquals(writeFailure, publisherFailure.get());
        verify(publisher).failClosed(writeFailure);
    }

    @Test
    void surfacedPollFailureBeforeInitialAssignmentReportsStartupFailure() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(1, List.of());
        var membershipFailure = new AtomicReference<Throwable>();
        var failureReported = new CountDownLatch(1);
        consumer.schedulePollTask(() -> {
            throw new IllegalStateException("membership failed before assignment");
        });
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher(),
            writeGate(),
            assignmentTracker("node-a"),
            1,
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
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> routingState.admitConnection("without-an-assignment")
        );
        membership.close();
    }

    @Test
    void revocationWithLiveConnectionsWaitsForTheirFinalAcknowledgement() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(2, List.of(0, 1));
        routingState.register("draining", 1);
        var publisher = publisher();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            writeGate(),
            assignmentTracker("node-a"),
            1,
            () -> {},
            ignored -> {},
            ignored -> {}
        );

        membership.onPartitionsRevoked(List.of(new TopicPartition(TOPIC, 1)));

        assertEquals(List.of(0, 1), routingState.assignedPartitions());
        membership.onPartitionsAssigned(List.of());
        assertEquals(List.of(0), routingState.assignedPartitions());
        verify(publisher, org.mockito.Mockito.never())
            .publishSelfNoMoreWrites(any(CaptureRoutingState.SelfRelease.class));
    }

    @Test
    void closeBeforeStartClosesTheConsumerWithoutWaitingForAPollThread() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            new CaptureRoutingState(1, List.of()),
            publisher(),
            writeGate(),
            assignmentTracker("node-a"),
            1,
            () -> {},
            ignored -> {},
            ignored -> {}
        );

        membership.close();

        assertTrue(consumer.closed());
    }

    private static CaptureKafkaPublisher publisher() {
        var publisher = mock(CaptureKafkaPublisher.class);
        when(publisher.publishSelfNoMoreWrites(any(CaptureRoutingState.SelfRelease.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        return publisher;
    }

    @SuppressWarnings("unchecked")
    private static CaptureKafkaMembership membership(CaptureRoutingState routingState) {
        return new CaptureKafkaMembership(
            mock(org.apache.kafka.clients.consumer.Consumer.class),
            TOPIC,
            routingState,
            publisher(),
            writeGate(),
            assignmentTracker("node-a"),
            1,
            () -> {},
            ignored -> {},
            ignored -> {}
        );
    }

    private static CaptureKafkaWriteGate writeGate() {
        return new CaptureKafkaWriteGate();
    }

    private static CaptureMembershipAssignmentTracker assignmentTracker(String... members) {
        var tracker = new CaptureMembershipAssignmentTracker();
        tracker.replaceMembers(Set.of(members));
        return tracker;
    }
}
