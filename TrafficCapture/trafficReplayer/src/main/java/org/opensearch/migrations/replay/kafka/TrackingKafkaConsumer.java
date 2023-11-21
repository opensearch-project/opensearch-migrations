package org.opensearch.migrations.replay.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.event.Level;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This is a wrapper around Kafka's Consumer class that provides tracking of partitions
 * and their current (asynchronously 'committed' by the calling contexts) offsets.  It
 * manages those offsets and the 'active' set of records that have been rendered by this
 * consumer, when to pause a poll loop(), and how to deal with consumer rebalances.
 */
@Slf4j
public class TrackingKafkaConsumer implements ConsumerRebalanceListener {

    /**
     * The keep-alive should already be set to a fraction of the max poll timeout for
     * the consumer (done outside of this class).  The keep-alive tells this class how
     * often the caller should be interacting with touch() and poll() calls.  As such,
     * we want to set up a long enough poll to not overwhelm a broker or client with
     * many empty poll() message responses.  We also don't want to poll() for so long
     * when there aren't messages that there isn't enough time to commit messages,
     * which happens after we poll() (on the same thread, as per Consumer requirements).
     */
    public static final int POLL_TIMEOUT_KEEP_ALIVE_DIVISOR = 4;
    private final Consumer<String, byte[]> kafkaConsumer;

    final String topic;
    private final Clock clock;
    /**
     * This collection holds the definitive list, as per the rebalance callback, of the partitions
     * that are currently assigned to this consumer.  The objects are removed when partitions are
     * revoked and new objects are only created/inserted when they're assigned.  That means that
     * the generations of each OffsetLifecycleTracker value may be different.
     */
    final Map<Integer, OffsetLifecycleTracker> partitionToOffsetLifecycleTrackerMap;
    // loosening visibility so that a unit test can read this
    final Map<TopicPartition, OffsetAndMetadata> nextSetOfCommitsMap;
    private final Duration keepAliveInterval;
    private final AtomicReference<Instant> lastTouchTimeRef;
    private int consumerConnectionGeneration;
    private boolean hasPendingCommitsToSend;

    public TrackingKafkaConsumer(Consumer<String, byte[]> kafkaConsumer, String topic,
                                 Duration keepAliveInterval, Clock c) {
        this.kafkaConsumer = kafkaConsumer;
        this.topic = topic;
        this.clock = c;
        this.partitionToOffsetLifecycleTrackerMap = new HashMap<>();
        this.nextSetOfCommitsMap = new HashMap<>();
        this.lastTouchTimeRef = new AtomicReference<>(Instant.EPOCH);
        this.keepAliveInterval = keepAliveInterval;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        safeCommit();
        partitions.forEach(p->{
            nextSetOfCommitsMap.remove(new TopicPartition(topic, p.partition()));
            partitionToOffsetLifecycleTrackerMap.remove(p.partition());
        });
        if (hasPendingCommitsToSend) {
            hasPendingCommitsToSend = partitionToOffsetLifecycleTrackerMap.values().stream()
                    .anyMatch(olt -> !olt.isEmpty());
        }
        log.atWarn().setMessage(()->this+"partitions revoked for "+partitions.stream()
                .map(p->p+"").collect(Collectors.joining(","))).log();
    }

    @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        ++consumerConnectionGeneration;
        partitions.forEach(p->partitionToOffsetLifecycleTrackerMap.computeIfAbsent(p.partition(),
                x->new OffsetLifecycleTracker(consumerConnectionGeneration)));
        log.atWarn().setMessage(()->this+"partitions added for "+partitions.stream()
                .map(p->p+"").collect(Collectors.joining(","))).log();
    }

    public void close() {
        log.atInfo().setMessage(()->"Kafka consumer closing.  " +
                "Committing (implicitly by Kafka's consumer): " + nextCommitsToString()).log();
        kafkaConsumer.close();
    }

    public Optional<Instant> getNextRequiredTouch() {
        return hasPendingCommitsToSend ? Optional.of(lastTouchTimeRef.get().plus(keepAliveInterval)) : Optional.empty();
    }

    public void touch() {
        log.trace("touch() called.");
        pause();
        try {
            var records = kafkaConsumer.poll(Duration.ZERO);
            if (!records.isEmpty()) {
                throw new IllegalStateException("Expected no entries once the consumer was paused.  " +
                        "This may have happened because a new assignment slipped into the consumer AFTER pause calls.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Unable to poll the topic: " + topic + " with our Kafka consumer. " +
                    "Swallowing and awaiting next metadata refresh to try again.").log();
        } finally {
            resume();
        }
        safeCommit();
        lastTouchTimeRef.set(clock.instant());
    }

    private void pause() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.pause(activePartitions);
        } catch (IllegalStateException e) {
            log.atError().setCause(e).setMessage(()->"Unable to pause the topic partitions: " + topic + ".  " +
                    "The active partitions passed here : " + activePartitions.stream()
                            .map(x->x.toString()).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions as tracked here are: " + getActivePartitions().stream()
                            .map(x->x.toString()).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions according to the consumer:  " + kafkaConsumer.assignment().stream()
                            .map(x->x.toString()).collect(Collectors.joining(","))
                    ).log();
        }
    }

    private void resume() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.pause(activePartitions);
        } catch (IllegalStateException e) {
            log.atError().setCause(e).setMessage(()->"Unable to resume the topic partitions: " + topic + ".  " +
                    "This may not be a fatal error for the entire process as the consumer should eventually"
                    + " rejoin and rebalance.  " +
                    "The active partitions passed here : " + activePartitions.stream()
                    .map(x->x.toString()).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions as tracked here are: " + getActivePartitions().stream()
                    .map(x->x.toString()).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions according to the consumer:  " + kafkaConsumer.assignment().stream()
                    .map(x->x.toString()).collect(Collectors.joining(","))
            ).log();
        }
    }

    public <K> K createAndTrackKey(int partition, long offset, Function<PojoKafkaCommitOffsetData, K> keyFactory) {
        var offsetTracker = partitionToOffsetLifecycleTrackerMap.get(partition);
        offsetTracker.add(offset);
        return keyFactory.apply(new PojoKafkaCommitOffsetData(consumerConnectionGeneration, partition, offset));
    }

    private Collection<TopicPartition> getActivePartitions() {
        return partitionToOffsetLifecycleTrackerMap.keySet().stream()
                .map(p->new TopicPartition(topic,p)).collect(Collectors.toList());
    }

    public ConsumerRecords<String, byte[]> getNextBatchOfRecords() {
        var records = safePollWithSwallowedRuntimeExceptions();
        safeCommit();
        return records;
    }

    private ConsumerRecords<String, byte[]> safePollWithSwallowedRuntimeExceptions() {
        try {
            lastTouchTimeRef.set(clock.instant());
            var records = kafkaConsumer.poll(keepAliveInterval.dividedBy(POLL_TIMEOUT_KEEP_ALIVE_DIVISOR));
            log.atLevel(records.isEmpty()? Level.TRACE:Level.INFO)
                    .setMessage(()->"Kafka consumer poll has fetched "+records.count()+" records").log();
            log.atTrace().setMessage(()->"All positions: {"+kafkaConsumer.assignment().stream()
                    .map(tp->tp+": "+kafkaConsumer.position(tp)).collect(Collectors.joining(",")) + "}").log();
            log.atTrace().setMessage(()->"All previously COMMITTED positions: {"+kafkaConsumer.assignment().stream()
                    .map(tp->tp+": "+kafkaConsumer.committed(tp)).collect(Collectors.joining(",")) + "}").log();
            return records;
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Unable to poll the topic: {} with our Kafka consumer. " +
                    "Swallowing and awaiting next metadata refresh to try again.").addArgument(topic).log();
            return new ConsumerRecords<>(Collections.emptyMap());
        }
    }

    void commitKafkaKey(KafkaCommitOffsetData kafkaTsk) {
        if (kafkaTsk.getGeneration() != consumerConnectionGeneration) {
            log.atWarn().setMessage(()->"trafficKey's generation (" + kafkaTsk.getGeneration() + ") is not current (" +
                    consumerConnectionGeneration + ").  Dropping this commit request since the record would have " +
                    "been or will be handled again by a current consumer within this process or another. Full key=" +
                    kafkaTsk).log();
            return;
        }
        var p = kafkaTsk.getPartition();
        Optional<Long> newHeadValue;

        newHeadValue = partitionToOffsetLifecycleTrackerMap.get(p).removeAndReturnNewHead(kafkaTsk.getOffset());
        newHeadValue.ifPresent(o -> {
            hasPendingCommitsToSend = true;
            nextSetOfCommitsMap.put(new TopicPartition(topic, p), new OffsetAndMetadata(o));
        });
    }

    private void safeCommit() {
        try {
            if (hasPendingCommitsToSend) {
                log.atDebug().setMessage(() -> "Committing " + nextSetOfCommitsMap).log();
                kafkaConsumer.commitSync(nextSetOfCommitsMap);
                log.atDebug().setMessage(() -> "Done committing " + nextSetOfCommitsMap).log();
                nextSetOfCommitsMap.clear();
            }
        } catch (RuntimeException e) {
            log.atWarn().setCause(e)
                    .setMessage(() -> "Error while committing.  " +
                            "Another consumer may already be processing messages before these commits.  " +
                            "Commits ARE NOT being discarded here, with the expectation that the revoked callback " +
                            "(onPartitionsRevoked) will be called.  " +
                            "Within that method, commits for unassigned partitions will be discarded.  " +
                            "After that, touch() or poll() will trigger another commit attempt." +
                            "Those calls will occur in the near future if assigned partitions have pending commits." +
                            nextSetOfCommitsMap.entrySet().stream()
                                    .map(kvp -> kvp.getKey() + "->" + kvp.getValue()).collect(Collectors.joining(",")))
                    .log();
        }
    }

    String nextCommitsToString() {
            return "nextCommits="+nextSetOfCommitsMap.entrySet().stream()
                    .map(kvp->kvp.getKey()+"->"+kvp.getValue()).collect(Collectors.joining(","));
    }
}
