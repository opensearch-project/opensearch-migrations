package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/**
 * Owns the Kafka consumer used only for proxy membership and partition assignment.
 */
@Slf4j
public final class CaptureKafkaMembership implements ConsumerRebalanceListener, AutoCloseable {
    static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    static final Duration DEFAULT_MAXIMUM_POLL_STALENESS = Duration.ofSeconds(30);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer;
    private final String topic;
    private final String nodeId;
    private final CapturePartitionAssignment partitionAssignment;
    private final CaptureKafkaPublisher publisher;
    private final CaptureKafkaWriteGate writeGate;
    private final Runnable initialAssignmentCallback;
    private final Consumer<Throwable> terminalFailureCallback;
    private final AtomicReference<Set<String>> observedMembers = new AtomicReference<>();
    private final AtomicBoolean initialAssignmentReported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final Thread pollThread;
    private AutoCloseable membershipObserverRegistration;

    public CaptureKafkaMembership(
        org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        String topic,
        String nodeId,
        CapturePartitionAssignment partitionAssignment,
        CaptureKafkaPublisher publisher,
        CaptureKafkaWriteGate writeGate,
        Runnable initialAssignmentCallback,
        Consumer<Throwable> terminalFailureCallback
    ) {
        this.consumer = Objects.requireNonNull(consumer);
        this.topic = Objects.requireNonNull(topic);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.partitionAssignment = Objects.requireNonNull(partitionAssignment);
        this.publisher = Objects.requireNonNull(publisher);
        this.writeGate = Objects.requireNonNull(writeGate);
        this.initialAssignmentCallback = Objects.requireNonNull(initialAssignmentCallback);
        this.terminalFailureCallback = Objects.requireNonNull(terminalFailureCallback);
        writeGate.addTerminalFailureListener(this::handleTerminalFailure);
        pollThread = new Thread(this::runPollLoop, "capture-kafka-membership");
        pollThread.setDaemon(true);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Capture Kafka membership has already been started");
        }
        if (closed.get()) {
            closeConsumerWithoutPollThread();
            return;
        }
        pollThread.start();
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        partitionAssignment.revokePartitions(partitionNumbers(partitions));
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        validateTopic(partitions);
        writeGate.recordSuccessfulPoll();
        if (writeGate.failureIfNotWritable() != null) {
            return;
        }
        partitionAssignment.replaceAssignedPartitions(partitionNumbers(partitions));
        consumer.pause(partitions);
        if (!partitionAssignment.assignedPartitions().isEmpty()
            && initialAssignmentReported.compareAndSet(false, true)) {
            initialAssignmentCallback.run();
        }
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        validateTopic(partitions);
        writeGate.trip(new IllegalStateException("Kafka membership lost partitions: " + partitions));
    }

    void observeMembership(Set<String> currentMembers) {
        var immutableCurrent = Set.copyOf(currentMembers);
        if (!immutableCurrent.contains(nodeId)) {
            writeGate.trip(new IllegalStateException(
                "Kafka membership view does not contain this proxy node " + nodeId
            ));
            return;
        }
        var previous = observedMembers.getAndSet(immutableCurrent);
        if (previous == null) {
            return;
        }
        previous.stream()
            .filter(departed -> !immutableCurrent.contains(departed))
            .filter(departed -> !departed.equals(nodeId))
            .forEach(this::declarePeerDeparture);
    }

    private void runPollLoop() {
        try {
            membershipObserverRegistration = CaptureCooperativeStickyAssignor.registerMembershipObserver(
                nodeId,
                this::observeMembership
            );
            consumer.subscribe(List.of(topic), this);
            while (!closed.get()) {
                var records = consumer.poll(POLL_INTERVAL);
                writeGate.recordSuccessfulPoll();
                if (!records.isEmpty()) {
                    writeGate.trip(new IllegalStateException(
                        "Paused capture membership consumer unexpectedly fetched traffic records"
                    ));
                    return;
                }
            }
        } catch (WakeupException e) {
            if (!closed.get()) {
                writeGate.trip(e);
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                writeGate.trip(t);
            }
        } finally {
            closeObserverRegistration();
            try {
                consumer.close(CLOSE_TIMEOUT);
                stopped.complete(null);
            } catch (Throwable t) {
                stopped.completeExceptionally(t);
            }
        }
    }

    private void declarePeerDeparture(String departedNodeId) {
        for (int partition = 0; partition < partitionAssignment.topicPartitionCount(); ++partition) {
            publisher.publishNoMoreWrites(departedNodeId, partition, nodeId)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        writeGate.trip(failure);
                    }
                });
        }
    }

    private void handleTerminalFailure(Throwable failure) {
        if (closed.compareAndSet(false, true)) {
            partitionAssignment.replaceAssignedPartitions(List.of());
            publisher.failClosed(failure);
            terminalFailureCallback.accept(failure);
            consumer.wakeup();
        }
    }

    private List<Integer> partitionNumbers(Collection<TopicPartition> partitions) {
        validateTopic(partitions);
        return partitions.stream().map(TopicPartition::partition).toList();
    }

    private void validateTopic(Collection<TopicPartition> partitions) {
        if (partitions.stream().anyMatch(partition -> !topic.equals(partition.topic()))) {
            throw new IllegalArgumentException("Kafka membership callback contained a different topic");
        }
    }

    private void closeObserverRegistration() {
        if (membershipObserverRegistration == null) {
            return;
        }
        try {
            membershipObserverRegistration.close();
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Unable to unregister capture membership observer").log();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (started.get()) {
                consumer.wakeup();
            } else {
                closeConsumerWithoutPollThread();
            }
        }
        try {
            stopped.join();
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Capture Kafka membership did not close cleanly").log();
        }
    }

    private void closeConsumerWithoutPollThread() {
        try {
            consumer.close(CLOSE_TIMEOUT);
            stopped.complete(null);
        } catch (Throwable t) {
            stopped.completeExceptionally(t);
        }
    }
}
