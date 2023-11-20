package org.opensearch.migrations.replay.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.event.Level;
//import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class TrackingKafkaConsumer implements ConsumerRebalanceListener {

    public static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(1);

    private final Consumer<String, byte[]> kafkaConsumer;

    final String topic;
    private final Clock clock;
    final ConcurrentHashMap<Integer, OffsetLifecycleTracker> partitionToOffsetLifecycleTrackerMap;
    // loosening visibility so that a unit test can read this
    final ConcurrentHashMap<TopicPartition, OffsetAndMetadata> nextSetOfCommitsMap;
    private final Duration keepAliveInterval;
    private final Object offsetLifecycleLock = new Object();
    private int consumerConnectionGeneration;
    private Instant lastPollTimestamp;

    public TrackingKafkaConsumer(Consumer<String, byte[]> kafkaConsumer, String topic,
                                 Duration keepAliveInterval, Clock c) {
        this.kafkaConsumer = kafkaConsumer;
        this.topic = topic;
        this.clock = c;
        this.partitionToOffsetLifecycleTrackerMap = new ConcurrentHashMap<>();
        this.nextSetOfCommitsMap = new ConcurrentHashMap<>();
        this.keepAliveInterval = keepAliveInterval;
        log.error("keepAliveInterval="+keepAliveInterval);
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        log.atWarn().setCause(new Exception()).setMessage(()->this+"partitions revoked for "+partitions.stream()
                .map(p->p+"").collect(Collectors.joining(","))).log();
        safeCommitWithRetry();
    }

    @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        log.atWarn().setCause(new Exception()).setMessage(()->this+"partitions added for "+partitions.stream()
                .map(p->p+"").collect(Collectors.joining(","))).log();
    }

    public void close() {
        kafkaConsumer.close();
        log.info("Kafka consumer closed successfully.");
    }

    public Optional<Instant> getNextRequiredTouch() {
        synchronized (offsetLifecycleLock) {
            return partitionToOffsetLifecycleTrackerMap.isEmpty() ? Optional.empty() :
                    Optional.of(lastPollTimestamp.plus(keepAliveInterval));
        }
    }

    public void touch() {
        log.error("TOUCH CALLED");
        try {
            //kafkaConsumer.pause();
            var records = kafkaConsumer.poll(Duration.ZERO);
            log.atError().setMessage(()->"Polled "+records.count()+" records to keep the consumer alive").log();
            records.forEach(r -> {
                try {
                    var tp = new TopicPartition(r.topic(), r.partition());
                    log.atError().setMessage(()->"Resetting "+tp+" to offset="+r.offset()).log();
                    kafkaConsumer.seek(tp, r.offset());
                } catch (IllegalStateException e) {
                    log.atWarn().setCause(e).setMessage(() -> "Caught exception while seeking.  " +
                            "Ignoring so that other records can have their seeks readjusted.").log();
                }
            });
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Unable to poll the topic: {} with our Kafka consumer. " +
                    "Swallowing and awaiting next metadata refresh to try again.").addArgument(topic).log();
            resetCommitInfo();
        }
        safeCommitWithRetry();
        lastPollTimestamp = clock.instant();
    }

    public <K> K createAndTrackKey(int partition, long offset,
                                   Function<KafkaCommitOffsetData.PojoKafkaCommitOffsetData, K> keyFactory) {
        synchronized (offsetLifecycleLock) {
            var offsetTracker = partitionToOffsetLifecycleTrackerMap.computeIfAbsent(partition,
                    p -> new OffsetLifecycleTracker());
            offsetTracker.add(offset);
        }
        return keyFactory.apply(new KafkaCommitOffsetData.PojoKafkaCommitOffsetData(consumerConnectionGeneration,
                partition, offset));
    }

    ConsumerRecords<String, byte[]> getNextBatchOfRecords() {
        safeCommitWithRetry();
        return safePollWithSwallowedRuntimeExceptions();
    }

    private ConsumerRecords<String, byte[]> safePollWithSwallowedRuntimeExceptions() {
        try {
            lastPollTimestamp = clock.instant();
            var records = kafkaConsumer.poll(CONSUMER_POLL_TIMEOUT);
            log.atLevel(records.isEmpty()? Level.TRACE:Level.INFO)
                    .setMessage(()->"Kafka consumer poll has fetched "+records.count()+" records").log();
            log.atDebug().setMessage(()->"All positions: {"+kafkaConsumer.assignment().stream()
                    .map(tp->tp+": "+kafkaConsumer.position(tp)).collect(Collectors.joining(",")) + "}").log();
            log.atDebug().setMessage(()->"All COMMITTED positions: {"+kafkaConsumer.assignment().stream()
                    .map(tp->tp+": "+kafkaConsumer.committed(tp)).collect(Collectors.joining(",")) + "}").log();
            return records;
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Unable to poll the topic: {} with our Kafka consumer. " +
                    "Swallowing and awaiting next metadata refresh to try again.").addArgument(topic).log();
            resetCommitInfo();
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
        synchronized (offsetLifecycleLock) {
            var tracker = partitionToOffsetLifecycleTrackerMap.get(p);
            newHeadValue = tracker.removeAndReturnNewHead(kafkaTsk);
            newHeadValue.ifPresent(o -> {
                if (tracker.isEmpty()) {
                    partitionToOffsetLifecycleTrackerMap.remove(p);
                }
                nextSetOfCommitsMap.put(new TopicPartition(topic, p), new OffsetAndMetadata(o));
            });
        }
    }

    private void safeCommitWithRetry() {
        synchronized (offsetLifecycleLock) {
            try {
                if (!nextSetOfCommitsMap.isEmpty()) {
                    log.atDebug().setMessage(() -> "Committing " + nextSetOfCommitsMap).log();
                    kafkaConsumer.commitSync(nextSetOfCommitsMap);
                    log.atDebug().setMessage(() -> "Done committing " + nextSetOfCommitsMap).log();
                }
                nextSetOfCommitsMap.clear();
            } catch (RuntimeException e) {
                log.atWarn().setCause(e)
                        .setMessage(() -> "Error while committing.  Purging all commit points since another consumer " +
                                "may have already begun processing messages BEFORE those commits.  Commits being " +
                                "discarded: " + nextSetOfCommitsMap.entrySet().stream()
                                .map(kvp -> kvp.getKey() + "->" + kvp.getValue()).collect(Collectors.joining(",")))
                        .log();
                resetCommitInfo();
            }
        }
    }

    private void resetCommitInfo() {
        ++consumerConnectionGeneration;
        nextSetOfCommitsMap.clear();
        partitionToOffsetLifecycleTrackerMap.clear();

    }

}
