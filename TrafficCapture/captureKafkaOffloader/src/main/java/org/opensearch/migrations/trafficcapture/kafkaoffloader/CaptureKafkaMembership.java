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
import org.apache.kafka.common.errors.RetriableException;
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
    private final CaptureAssignmentPublisher publisher;
    private final CaptureMembershipAssignmentTracker assignmentTracker;
    private final int minimumActiveProxyCount;
    private final Runnable initialAssignmentCallback;
    private final Consumer<Throwable> membershipFailureCallback;
    private final Consumer<Throwable> unstableProcessFailureCallback;
    private final Set<Integer> kafkaAssignment = new HashSet<>();
    private final AtomicBoolean initialAssignmentReported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final Thread pollThread;

    public CaptureKafkaMembership(
        org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        String topic,
        CaptureRoutingState routingState,
        CaptureAssignmentPublisher publisher,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        Runnable initialAssignmentCallback,
        Consumer<Throwable> membershipFailureCallback,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this.consumer = Objects.requireNonNull(consumer);
        this.topic = Objects.requireNonNull(topic);
        this.routingState = Objects.requireNonNull(routingState);
        kafkaAssignment.addAll(routingState.assignedPartitions());
        this.publisher = Objects.requireNonNull(publisher);
        this.assignmentTracker = Objects.requireNonNull(assignmentTracker);
        if (minimumActiveProxyCount <= 0) {
            throw new IllegalArgumentException("minimumActiveProxyCount must be positive");
        }
        this.minimumActiveProxyCount = minimumActiveProxyCount;
        this.initialAssignmentCallback = Objects.requireNonNull(initialAssignmentCallback);
        this.membershipFailureCallback = Objects.requireNonNull(membershipFailureCallback);
        this.unstableProcessFailureCallback = Objects.requireNonNull(unstableProcessFailureCallback);
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
        kafkaAssignment.addAll(partitionNumbers(partitions));
        consumer.pause(partitions);
        if (kafkaAssignment.isEmpty()) {
            return;
        }
        boolean minimumSatisfied = assignmentTracker.satisfiesMinimum(minimumActiveProxyCount);
        if (!initialAssignmentReported.get() && !minimumSatisfied) {
            return;
        }
        var assignmentSnapshot = List.copyOf(kafkaAssignment);
        publisher.installAssignment(assignmentSnapshot)
            .whenComplete((writerNodeId, failure) -> {
                if (failure != null) {
                    publisher.failClosed(failure);
                    return;
                }
                log.atInfo()
                    .setMessage("Installed proxy assignment writer {} for partitions {}")
                    .addArgument(writerNodeId)
                    .addArgument(routingState.assignedPartitions())
                    .log();
                if (initialAssignmentReported.compareAndSet(false, true)) {
                    initialAssignmentCallback.run();
                }
            });
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        kafkaAssignment.removeAll(partitionNumbers(partitions));
    }

    private void runPollLoop() {
        try {
            consumer.subscribe(List.of(topic), this);
            while (!closed.get()) {
                try {
                    var records = consumer.poll(POLL_INTERVAL);
                    if (!records.isEmpty()) {
                        handleMembershipFailure(new IllegalStateException(
                            "Paused capture membership consumer unexpectedly fetched traffic records"
                        ));
                        return;
                    }
                } catch (WakeupException e) {
                    if (!closed.get()) {
                        handleMembershipFailure(e);
                    }
                    return;
                } catch (RetriableException e) {
                    log.atWarn()
                        .setCause(e)
                        .setMessage(
                            "Transient Kafka membership poll failure; "
                                + "continuing with the last usable assignment while polling retries"
                        )
                        .log();
                } catch (Error e) {
                    if (!closed.get()) {
                        handleUnstableProcessFailure(e);
                    }
                    return;
                } catch (RuntimeException e) {
                    if (!closed.get()) {
                        handleMembershipFailure(e);
                    }
                    return;
                }
            }
        } catch (WakeupException e) {
            if (!closed.get()) {
                handleMembershipFailure(e);
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                if (t instanceof Error) {
                    handleUnstableProcessFailure(t);
                } else {
                    handleMembershipFailure(t);
                }
            }
        } finally {
            try {
                consumer.close(CLOSE_TIMEOUT);
                stopped.complete(null);
            } catch (Throwable t) {
                if (t instanceof Error) {
                    unstableProcessFailureCallback.accept(t);
                }
                stopped.completeExceptionally(t);
            }
        }
    }

    private void handleUnstableProcessFailure(Throwable failure) {
        if (closed.compareAndSet(false, true)) {
            unstableProcessFailureCallback.accept(failure);
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
        try {
            closeAsync().join();
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Capture Kafka membership did not close cleanly").log();
        }
    }

    CompletableFuture<Void> closeAsync() {
        if (closed.compareAndSet(false, true)) {
            if (started.get()) {
                consumer.wakeup();
            } else {
                var closeThread = new Thread(
                    this::closeConsumerWithoutPollThread,
                    "capture-kafka-membership-close"
                );
                closeThread.setDaemon(true);
                closeThread.start();
            }
        }
        return stopped;
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
