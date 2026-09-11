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
    void pollThreadPausesAssignmentsAndRevocationOnlyChangesNewAdmission() throws Exception {
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
        assertEquals(List.of(0), routingState.assignedPartitions());
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
    void cooperativeRevocationPromotesAnotherOwnedPartitionIntoTheConfiguredWidth() {
        var routingState = new CaptureRoutingState(3, 1, List.of());
        var membership = membership(routingState);
        var partition0 = new TopicPartition(TOPIC, 0);
        var partition1 = new TopicPartition(TOPIC, 1);

        membership.onPartitionsAssigned(List.of(partition0, partition1));
        membership.onPartitionsRevoked(List.of(partition0));
        membership.onPartitionsAssigned(List.of());

        assertEquals(List.of(1), routingState.assignedPartitions());
    }

    @Test
    void lostPartitionsFailClosedAndRemoveAllAdmissionChoices() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
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
            () -> {},
            terminalFailure::set
        );

        membership.onPartitionsLost(List.of(new TopicPartition(TOPIC, 1)));
        membership.onPartitionsAssigned(List.of(new TopicPartition(TOPIC, 2)));

        assertEquals(List.of(), routingState.assignedPartitions());
        assertTrue(terminalFailure.get() instanceof IllegalStateException);
        verify(publisher).failClosed(terminalFailure.get());
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
            () -> {},
            ignored -> {}
        );

        membership.onPartitionsRevoked(List.of(new TopicPartition(TOPIC, 1)));

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
            () -> {},
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
            () -> {},
            ignored -> {}
        );
    }

    private static CaptureKafkaWriteGate writeGate() {
        return new CaptureKafkaWriteGate(
            CaptureKafkaMembership.DEFAULT_MAXIMUM_POLL_STALENESS,
            System::nanoTime
        );
    }
}
