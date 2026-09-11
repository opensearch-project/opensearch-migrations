package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer;
    private final String topic;
    private final CaptureRoutingState routingState;
    private final CaptureKafkaPublisher publisher;
    private final CaptureKafkaWriteGate writeGate;
    private final CaptureMembershipAssignmentTracker assignmentTracker;
    private final int minimumActiveProxyCount;
    private final Runnable initialAssignmentCallback;
    private final Consumer<Throwable> membershipFailureCallback;
    private final Consumer<Throwable> publisherFailureCallback;
    private final Set<Integer> kafkaAssignment = new HashSet<>();
    private final AtomicBoolean initialAssignmentReported = new AtomicBoolean();
    private final AtomicBoolean publisherFailureReported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final Thread pollThread;

    public CaptureKafkaMembership(
        org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        String topic,
        CaptureRoutingState routingState,
        CaptureKafkaPublisher publisher,
        CaptureKafkaWriteGate writeGate,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        Runnable initialAssignmentCallback,
        Consumer<Throwable> membershipFailureCallback,
        Consumer<Throwable> publisherFailureCallback
    ) {
        this.consumer = Objects.requireNonNull(consumer);
        this.topic = Objects.requireNonNull(topic);
        this.routingState = Objects.requireNonNull(routingState);
        kafkaAssignment.addAll(routingState.assignedPartitions());
        this.publisher = Objects.requireNonNull(publisher);
        this.writeGate = Objects.requireNonNull(writeGate);
        this.assignmentTracker = Objects.requireNonNull(assignmentTracker);
        if (minimumActiveProxyCount <= 0) {
            throw new IllegalArgumentException("minimumActiveProxyCount must be positive");
        }
        this.minimumActiveProxyCount = minimumActiveProxyCount;
        this.initialAssignmentCallback = Objects.requireNonNull(initialAssignmentCallback);
        this.membershipFailureCallback = Objects.requireNonNull(membershipFailureCallback);
        this.publisherFailureCallback = Objects.requireNonNull(publisherFailureCallback);
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
        kafkaAssignment.removeAll(partitionNumbers(partitions));
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        validateTopic(partitions);
        if (writeGate.failureIfNotWritable() != null) {
            return;
        }
        kafkaAssignment.addAll(partitionNumbers(partitions));
        consumer.pause(partitions);
        if (kafkaAssignment.isEmpty()) {
            return;
        }
        boolean minimumSatisfied = assignmentTracker.satisfiesMinimum(minimumActiveProxyCount);
        if (!initialAssignmentReported.get() && !minimumSatisfied) {
            return;
        }
        routingState.replaceAssignedPartitions(kafkaAssignment)
            .forEach(this::publishSelfRelease);
        if (initialAssignmentReported.compareAndSet(false, true)) {
            initialAssignmentCallback.run();
        }
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        kafkaAssignment.removeAll(partitionNumbers(partitions));
    }

    private void runPollLoop() {
        try {
            consumer.subscribe(List.of(topic), this);
            while (!closed.get()) {
                var records = consumer.poll(POLL_INTERVAL);
                if (!records.isEmpty()) {
                    handleMembershipFailure(new IllegalStateException(
                        "Paused capture membership consumer unexpectedly fetched traffic records"
                    ));
                    return;
                }
            }
        } catch (WakeupException e) {
            if (!closed.get()) {
                handleMembershipFailure(e);
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                handleMembershipFailure(t);
            }
        } finally {
            try {
                consumer.close(CLOSE_TIMEOUT);
                stopped.complete(null);
            } catch (Throwable t) {
                stopped.completeExceptionally(t);
            }
        }
    }

    private void publishSelfRelease(CaptureRoutingState.SelfRelease release) {
        publisher.publishSelfNoMoreWrites(release)
            .whenComplete((ignored, failure) -> {
                if (failure != null) {
                    writeGate.trip(failure);
                }
            });
    }

    private void handleTerminalFailure(Throwable failure) {
        if (publisherFailureReported.compareAndSet(false, true)) {
            routingState.replaceAssignedPartitions(List.of());
            publisher.failClosed(failure);
            publisherFailureCallback.accept(failure);
            if (closed.compareAndSet(false, true)) {
                consumer.wakeup();
            }
        }
    }

    private void handleMembershipFailure(Throwable failure) {
        if (closed.compareAndSet(false, true)) {
            if (initialAssignmentReported.get()) {
                log.atError()
                    .setCause(failure)
                    .setMessage(
                        "Kafka membership stopped after the initial assignment; "
                            + "continuing capture with the last completed assignment"
                    )
                    .log();
            } else {
                membershipFailureCallback.accept(failure);
            }
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
