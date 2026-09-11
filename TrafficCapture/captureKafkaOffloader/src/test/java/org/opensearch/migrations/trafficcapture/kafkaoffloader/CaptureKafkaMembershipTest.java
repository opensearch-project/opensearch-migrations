package org.opensearch.migrations.trafficcapture.kafkaoffloader;

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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureKafkaMembershipTest {
    private static final String TOPIC = "traffic";
    private static final String ACTIVATION_ID = "activation";

    @Test
    void revocationKeepsLastUsableAssignmentUntilReplacementManifestIsAcknowledged() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var publisher = publisher(routingState);
        var initialAssignment = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            assignmentTracker("node-a"),
            1,
            initialAssignment::countDown,
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
        var duringRebalance = routingState.admitConnection("during-rebalance");
        assertEquals("activation:1", duringRebalance.writerNodeId());
        assertTrue(List.of(0, 1).contains(duringRebalance.partition()));

        membership.onPartitionsAssigned(List.of());

        assertEquals(List.of(0), routingState.assignedPartitions());
        assertEquals("activation:2", routingState.admitConnection("after-replacement").writerNodeId());
        assertEquals(null, failure.get());
        membership.close();
        assertTrue(consumer.closed());
    }

    @Test
    void emptyCooperativeCallbackCanInstallTheRetainedMembershipAssignment() {
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var membership = membership(routingState);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);

        membership.onPartitionsAssigned(List.of(partition0, partition1));
        membership.onPartitionsRevoked(List.of(partition1));
        membership.onPartitionsAssigned(List.of());

        assertEquals(List.of(0), routingState.assignedPartitions());
        assertEquals("activation:2", routingState.currentWriterNodeId());
    }

    @Test
    void minimumMemberCountIsAStartupBarrierOnly() {
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var tracker = assignmentTracker("node-a");
        var initialAssignment = new java.util.concurrent.atomic.AtomicInteger();
        var membership = new CaptureKafkaMembership(
            mock(org.apache.kafka.clients.consumer.Consumer.class),
            TOPIC,
            routingState,
            publisher(routingState),
            tracker,
            2,
            initialAssignment::incrementAndGet,
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
        assertEquals(0, routingState.admitConnection("accepted").partition());

        tracker.replaceMembers(Set.of("node-a"));
        assertEquals(0, routingState.admitConnection("member-count-later-dropped").partition());
    }

    @Test
    void lostPartitionsDoNotChangeTheLastUsableAssignment() {
        @SuppressWarnings("unchecked")
        var consumer = mock(org.apache.kafka.clients.consumer.Consumer.class);
        var routingState = activeState(3, List.of(0, 1));
        var publisher = publisher(routingState);
        var membershipFailure = new AtomicReference<Throwable>();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher,
            assignmentTracker("node-a"),
            1,
            () -> {},
            membershipFailure::set
        );

        membership.onPartitionsLost(List.of(new TopicPartition(TOPIC, 1)));

        assertEquals(List.of(0, 1), routingState.assignedPartitions());
        assertEquals("activation:1", routingState.admitConnection("while-membership-is-changing").writerNodeId());
        assertEquals(null, membershipFailure.get());
        verify(publisher, org.mockito.Mockito.never()).failClosed(any());

        membership.onPartitionsAssigned(List.of(new TopicPartition(TOPIC, 2)));

        assertEquals(List.of(0, 2), routingState.assignedPartitions());
        assertEquals("activation:2", routingState.admitConnection("after-normal-assignment").writerNodeId());
    }

    @Test
    void surfacedPollFailureAfterInitialAssignmentKeepsAdmissionAndDoesNotAffectThePublisher()
        throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(routingState);
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
            assignmentTracker("node-a"),
            1,
            initialAssignment::countDown,
            membershipFailure::set
        );

        membership.start();

        assertTrue(initialAssignment.await(1, TimeUnit.SECONDS));
        long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!consumer.closed() && System.nanoTime() < closeDeadline) {
            Thread.sleep(1);
        }
        assertTrue(consumer.closed());
        membership.close();
        assertEquals(0, routingState.admitConnection("after-membership-failure").partition());
        assertEquals(null, membershipFailure.get());
        verify(publisher, org.mockito.Mockito.never()).failClosed(any());
    }

    @Test
    void surfacedPollFailureBeforeInitialAssignmentReportsStartupFailure() throws Exception {
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
            publisher(routingState),
            assignmentTracker("node-a"),
            1,
            () -> {},
            failure -> {
                membershipFailure.set(failure);
                failureReported.countDown();
            }
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
    void closeBeforeStartClosesTheConsumerWithoutWaitingForAPollThread() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            routingState,
            publisher(routingState),
            assignmentTracker("node-a"),
            1,
            () -> {},
            ignored -> {}
        );

        membership.close();

        assertTrue(consumer.closed());
    }

    @SuppressWarnings("unchecked")
    private static CaptureKafkaPublisher publisher(CaptureRoutingState routingState) {
        var publisher = mock(CaptureKafkaPublisher.class);
        when(publisher.installAssignment(any(Collection.class))).thenAnswer(invocation -> {
            Collection<Integer> partitions = invocation.getArgument(0);
            var assignment = routingState.prepareAssignment(partitions);
            routingState.prepareInitialManifests(assignment);
            routingState.activateAssignment(assignment);
            return CompletableFuture.completedFuture(assignment.writerNodeId());
        });
        return publisher;
    }

    @SuppressWarnings("unchecked")
    private static CaptureKafkaMembership membership(CaptureRoutingState routingState) {
        return new CaptureKafkaMembership(
            mock(org.apache.kafka.clients.consumer.Consumer.class),
            TOPIC,
            routingState,
            publisher(routingState),
            assignmentTracker("node-a"),
            1,
            () -> {},
            ignored -> {}
        );
    }

    private static CaptureRoutingState activeState(int partitionCount, List<Integer> partitions) {
        var state = new CaptureRoutingState(ACTIVATION_ID, partitionCount);
        var assignment = state.prepareAssignment(partitions);
        state.prepareInitialManifests(assignment);
        state.activateAssignment(assignment);
        return state;
    }

    private static CaptureMembershipAssignmentTracker assignmentTracker(String... members) {
        var tracker = new CaptureMembershipAssignmentTracker();
        tracker.replaceMembers(Set.of(members));
        return tracker;
    }
}
