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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureKafkaMembershipTest {
    private static final String TOPIC = "traffic";
    private static final String NODE_ID = "node-a";

    @Test
    void pollThreadPausesAssignmentsAndRevocationOnlyChangesNewAdmission() throws Exception {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var assignment = new CapturePartitionAssignment(3, List.of());
        var publisher = publisher();
        var initialAssignment = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            NODE_ID,
            assignment,
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

        assertEquals(List.of(0, 1), assignment.assignedPartitions());
        assertEquals(Set.of(partition0, partition1), consumer.paused());
        membership.onPartitionsRevoked(List.of(partition1));
        assertEquals(List.of(0), assignment.assignedPartitions());
        assertEquals(null, failure.get());
        membership.close();
        assertTrue(consumer.closed());
    }

    @Test
    void aPreviouslyObservedPeerDepartureIsDeclaredOnEveryTopicPartition() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var assignment = new CapturePartitionAssignment(3, List.of(0));
        var publisher = publisher();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            NODE_ID,
            assignment,
            publisher,
            writeGate(),
            () -> {},
            ignored -> {}
        );

        membership.observeMembership(Set.of(NODE_ID, "node-b"));
        membership.observeMembership(Set.of(NODE_ID));

        verify(publisher).publishNoMoreWrites("node-b", 0, NODE_ID);
        verify(publisher).publishNoMoreWrites("node-b", 1, NODE_ID);
        verify(publisher).publishNoMoreWrites("node-b", 2, NODE_ID);
    }

    @Test
    void lostPartitionsFailClosedAndRemoveAllAdmissionChoices() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var assignment = new CapturePartitionAssignment(3, List.of(0, 1));
        var publisher = publisher();
        var terminalFailure = new AtomicReference<Throwable>();
        var writeGate = writeGate();
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            NODE_ID,
            assignment,
            publisher,
            writeGate,
            () -> {},
            terminalFailure::set
        );

        membership.onPartitionsLost(List.of(new TopicPartition(TOPIC, 1)));
        membership.onPartitionsAssigned(List.of(new TopicPartition(TOPIC, 2)));

        assertEquals(List.of(), assignment.assignedPartitions());
        assertTrue(terminalFailure.get() instanceof IllegalStateException);
        verify(publisher).failClosed(terminalFailure.get());
    }

    @Test
    void closeBeforeStartClosesTheConsumerWithoutWaitingForAPollThread() {
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var membership = new CaptureKafkaMembership(
            consumer,
            TOPIC,
            NODE_ID,
            new CapturePartitionAssignment(1, List.of()),
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
        when(publisher.publishNoMoreWrites(anyString(), anyInt(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        return publisher;
    }

    private static CaptureKafkaWriteGate writeGate() {
        return new CaptureKafkaWriteGate(
            CaptureKafkaMembership.DEFAULT_MAXIMUM_POLL_STALENESS,
            System::nanoTime
        );
    }
}
