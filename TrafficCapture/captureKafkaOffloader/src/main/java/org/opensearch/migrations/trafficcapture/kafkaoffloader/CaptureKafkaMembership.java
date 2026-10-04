package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;

/**
 * Maintains Kafka group membership for load-balancing newly accepted source connections. The
 * consumer is deliberately paused and never processes traffic records; its partition set is input
 * to a new routing generation, not an ownership boundary for connections that already exist.
 *
 * <p>A nonempty Kafka assignment is made usable only after its routing generation has persisted an
 * initial heartbeat on every writer-partition lane. Revocations, partition loss, and later
 * membership failure do not invalidate immutable routes from an earlier generation, so existing
 * connections can drain without changing writer identity or partition.
 */
@Slf4j
public final class CaptureKafkaMembership implements ConsumerRebalanceListener, AutoCloseable {
    static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer;
    private final String topic;
    private final CaptureRoutingState routingState;
    private final CaptureRoutingGenerationPublisher routingGenerationPublisher;
    private final Runnable initialRoutingReadyCallback;
    private final Consumer<Throwable> membershipFailureCallback;
    private final Consumer<Throwable> unstableProcessFailureCallback;
    private final Set<Integer> kafkaAssignment = new HashSet<>();
    private final AtomicBoolean initialRoutingReadyReported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final Thread pollThread;

    public CaptureKafkaMembership(
        @NonNull org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        @NonNull String topic,
        @NonNull CaptureRoutingState routingState,
        @NonNull CaptureRoutingGenerationPublisher routingGenerationPublisher,
        @NonNull Runnable initialRoutingReadyCallback,
        @NonNull Consumer<Throwable> membershipFailureCallback,
        @NonNull Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this.consumer = consumer;
        this.topic = topic;
        this.routingState = routingState;
        kafkaAssignment.addAll(routingState.activeRoutingPartitions());
        this.routingGenerationPublisher = routingGenerationPublisher;
        this.initialRoutingReadyCallback = initialRoutingReadyCallback;
        this.membershipFailureCallback = membershipFailureCallback;
        this.unstableProcessFailureCallback = unstableProcessFailureCallback;
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
        var kafkaAssignmentSnapshot = List.copyOf(kafkaAssignment);
        routingGenerationPublisher.initializeRoutingGeneration(kafkaAssignmentSnapshot)
            .whenComplete((writerNodeId, failure) -> {
                if (failure != null) {
                    routingGenerationPublisher.stopAfterFailure(failure);
                    return;
                }
                log.atInfo()
                    .setMessage("Initialized proxy routing writer {} for partitions {}")
                    .addArgument(writerNodeId)
                    .addArgument(routingState.activeRoutingPartitions())
                    .log();
                if (initialRoutingReadyReported.compareAndSet(false, true)) {
                    initialRoutingReadyCallback.run();
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
            while (!closed.get() && pollOnce()) {
                // Continue Kafka group maintenance until closure or a terminal poll result.
            }
        } catch (Throwable t) {
            handlePollLoopFailure(t);
        } finally {
            closeConsumerAfterPollLoop();
        }
    }

    private boolean pollOnce() {
        try {
            var records = consumer.poll(POLL_INTERVAL);
            if (!records.isEmpty()) {
                handleMembershipFailure(new IllegalStateException(
                    "Paused capture membership consumer unexpectedly fetched traffic records"
                ));
                return false;
            }
            return true;
        } catch (RetriableException e) {
            log.atWarn()
                .setCause(e)
                .setMessage(
                    "Transient Kafka membership poll failure; "
                        + "continuing with the active routing generation while polling retries"
                )
                .log();
            return true;
        }
    }

    private void handlePollLoopFailure(Throwable failure) {
        if (closed.get()) {
            return;
        }
        if (failure instanceof Error) {
            handleUnstableProcessFailure(failure);
        } else {
            handleMembershipFailure(failure);
        }
    }

    private void closeConsumerAfterPollLoop() {
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

    private void handleUnstableProcessFailure(Throwable failure) {
        if (closed.compareAndSet(false, true)) {
            unstableProcessFailureCallback.accept(failure);
        }
    }

    private void handleMembershipFailure(Throwable failure) {
        if (closed.compareAndSet(false, true)) {
            if (initialRoutingReadyReported.get()) {
                log.atError()
                    .setCause(failure)
                    .setMessage(
                        "Kafka membership stopped after initial routing became usable; "
                            + "continuing capture with the active routing generation"
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
