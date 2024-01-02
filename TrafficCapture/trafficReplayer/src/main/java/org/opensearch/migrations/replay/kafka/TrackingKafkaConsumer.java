package org.opensearch.migrations.replay.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.KafkaConsumerContexts;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.slf4j.event.Level;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * This is a wrapper around Kafka's Consumer class that provides tracking of partitions
 * and their current (asynchronously 'committed' by the calling contexts) offsets.  It
 * manages those offsets and the 'active' set of records that have been rendered by this
 * consumer, when to pause a poll loop(), and how to deal with consumer rebalances.
 */
@Slf4j
public class TrackingKafkaConsumer implements ConsumerRebalanceListener {
    @AllArgsConstructor
    private static class OrderedKeyHolder implements Comparable<OrderedKeyHolder> {
        @Getter final long offset;
        @Getter @NonNull final ITrafficStreamKey tsk;

        @Override
        public int compareTo(OrderedKeyHolder o) {
            return Long.compare(offset, o.offset);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            OrderedKeyHolder that = (OrderedKeyHolder) o;

            if (offset != that.offset) return false;
            return tsk.equals(that.tsk);
        }

        @Override
        public int hashCode() {
            return Long.valueOf(offset).hashCode();
        }
    }

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

    @NonNull private final IInstrumentationAttributes globalContext;
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
    private final Object commitDataLock = new Object();
    // loosening visibility so that a unit test can read this
    final Map<TopicPartition, OffsetAndMetadata> nextSetOfCommitsMap;
    final Map<TopicPartition, PriorityQueue<OrderedKeyHolder>> nextSetOfKeysContextsBeingCommitted;
    final java.util.function.Consumer<ITrafficStreamKey> onCommitKeyCallback;
    private final Duration keepAliveInterval;
    private final AtomicReference<Instant> lastTouchTimeRef;
    private final AtomicInteger consumerConnectionGeneration;
    private final AtomicInteger kafkaRecordsLeftToCommitEventually;
    private final AtomicBoolean kafkaRecordsReadyToCommit;

    public TrackingKafkaConsumer(@NonNull IInstrumentationAttributes globalContext,
                                 Consumer<String, byte[]> kafkaConsumer, String topic,
                                 Duration keepAliveInterval, Clock c,
                                 java.util.function.Consumer<ITrafficStreamKey> onCommitKeyCallback) {
        this.globalContext = globalContext;
        this.kafkaConsumer = kafkaConsumer;
        this.topic = topic;
        this.clock = c;
        this.partitionToOffsetLifecycleTrackerMap = new HashMap<>();
        this.nextSetOfCommitsMap = new HashMap<>();
        this.nextSetOfKeysContextsBeingCommitted = new HashMap<>();
        this.lastTouchTimeRef = new AtomicReference<>(Instant.EPOCH);
        consumerConnectionGeneration = new AtomicInteger();
        kafkaRecordsLeftToCommitEventually = new AtomicInteger();
        kafkaRecordsReadyToCommit = new AtomicBoolean();
        this.keepAliveInterval = keepAliveInterval;
        this.onCommitKeyCallback = onCommitKeyCallback;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        new KafkaConsumerContexts.AsyncListeningContext(globalContext).onPartitionsRevoked(partitions);
        synchronized (commitDataLock) {
            safeCommit(globalContext);
            partitions.forEach(p -> {
                var tp = new TopicPartition(topic, p.partition());
                nextSetOfCommitsMap.remove(tp);
                nextSetOfKeysContextsBeingCommitted.remove(tp);
                partitionToOffsetLifecycleTrackerMap.remove(p.partition());
            });
            kafkaRecordsLeftToCommitEventually.set(partitionToOffsetLifecycleTrackerMap.values().stream()
                    .mapToInt(OffsetLifecycleTracker::size).sum());
            kafkaRecordsReadyToCommit.set(!nextSetOfCommitsMap.values().isEmpty());
            log.atWarn().setMessage(() -> this + "partitions revoked for " + partitions.stream()
                    .map(p -> p + "").collect(Collectors.joining(","))).log();
        }
    }

    @Override public void onPartitionsAssigned(Collection<TopicPartition> newPartitions) {
        new KafkaConsumerContexts.AsyncListeningContext(globalContext).onPartitionsAssigned(newPartitions);
        synchronized (commitDataLock) {
            consumerConnectionGeneration.incrementAndGet();
            newPartitions.forEach(p -> partitionToOffsetLifecycleTrackerMap.computeIfAbsent(p.partition(),
                    x -> new OffsetLifecycleTracker(consumerConnectionGeneration.get())));
            log.atWarn().setMessage(() -> this + "partitions added for " + newPartitions.stream()
                    .map(p -> p + "").collect(Collectors.joining(","))).log();
        }
    }

    public void close() {
        log.atInfo().setMessage(()->"Kafka consumer closing.  " +
                "Committing (implicitly by Kafka's consumer): " + nextCommitsToString()).log();
        kafkaConsumer.close();
    }

    public Optional<Instant> getNextRequiredTouch() {
        var lastTouchTime = lastTouchTimeRef.get();
        var r = kafkaRecordsLeftToCommitEventually.get() == 0 ? Optional.<Instant>empty()
                : Optional.of(kafkaRecordsReadyToCommit.get() ? Instant.now() : lastTouchTime.plus(keepAliveInterval));
        log.atTrace().setMessage(()->"returning next required touch at " + r.map(t->""+t).orElse("N/A") +
                " from a lastTouchTime of "+lastTouchTime).log();
        return r;
    }

    public void touch(IInstrumentationAttributes context) {
        try (var touchCtx = new KafkaConsumerContexts.TouchScopeContext(context)) {
            log.trace("touch() called.");
            pause();
            try (var pollCtx = new KafkaConsumerContexts.PollScopeContext(touchCtx)) {
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
            safeCommit(context);
            lastTouchTimeRef.set(clock.instant());
        }
    }

    private void pause() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.pause(activePartitions);
        } catch (IllegalStateException e) {
            log.atError().setCause(e).setMessage(()->"Unable to pause the topic partitions: " + topic + ".  " +
                    "The active partitions passed here : " + activePartitions.stream()
                            .map(x->""+x).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions as tracked here are: " + getActivePartitions().stream()
                            .map(x->""+x).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions according to the consumer:  " + kafkaConsumer.assignment().stream()
                            .map(x->""+x).collect(Collectors.joining(","))
                    ).log();
        }
    }

    private void resume() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.resume(activePartitions);
        } catch (IllegalStateException e) {
            log.atError().setCause(e).setMessage(()->"Unable to resume the topic partitions: " + topic + ".  " +
                    "This may not be a fatal error for the entire process as the consumer should eventually"
                    + " rejoin and rebalance.  " +
                    "The active partitions passed here : " + activePartitions.stream()
                    .map(x->""+x).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions as tracked here are: " + getActivePartitions().stream()
                    .map(x->""+x).collect(Collectors.joining(",")) + ".  " +
                    "The active partitions according to the consumer:  " + kafkaConsumer.assignment().stream()
                    .map(x->""+x).collect(Collectors.joining(","))
            ).log();
        }
    }

    private Collection<TopicPartition> getActivePartitions() {
        return partitionToOffsetLifecycleTrackerMap.keySet().stream()
                .map(p->new TopicPartition(topic,p)).collect(Collectors.toList());
    }

    public <T> Stream<T>
    getNextBatchOfRecords(IInstrumentationAttributes context,
                          BiFunction<KafkaCommitOffsetData, ConsumerRecord<String,byte[]>, T> builder) {
        safeCommit(context);
        var records = safePollWithSwallowedRuntimeExceptions(context);
        safeCommit(context);
        return applyBuilder(builder, records);
    }

    private <T> Stream<T> applyBuilder(BiFunction<KafkaCommitOffsetData,ConsumerRecord<String,byte[]>,T> builder,
                                       ConsumerRecords<String,byte[]> records) {
        return StreamSupport.stream(records.spliterator(), false)
                .map(kafkaRecord -> {
                    var offsetTracker = partitionToOffsetLifecycleTrackerMap.get(kafkaRecord.partition());
                    var offsetDetails = new PojoKafkaCommitOffsetData(offsetTracker.consumerConnectionGeneration,
                            kafkaRecord.partition(), kafkaRecord.offset());
                    offsetTracker.add(offsetDetails.getOffset());
                    kafkaRecordsLeftToCommitEventually.incrementAndGet();
                    log.atTrace().setMessage(()->"records in flight="+ kafkaRecordsLeftToCommitEventually.get()).log();
                    return builder.apply(offsetDetails, kafkaRecord);
                });
    }

    private ConsumerRecords<String, byte[]>
    safePollWithSwallowedRuntimeExceptions(IInstrumentationAttributes context) {
        try {
            lastTouchTimeRef.set(clock.instant());
            ConsumerRecords<String, byte[]> records;
            try (var pollContext = new KafkaConsumerContexts.PollScopeContext(context)) {
                records = kafkaConsumer.poll(keepAliveInterval.dividedBy(POLL_TIMEOUT_KEEP_ALIVE_DIVISOR));
            }
            log.atLevel(records.isEmpty()? Level.TRACE:Level.INFO)
                    .setMessage(()->"Kafka consumer poll has fetched "+records.count() + " records.  " +
                            "Records in flight=" + kafkaRecordsLeftToCommitEventually.get()).log();
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

    ITrafficCaptureSource.CommitResult commitKafkaKey(ITrafficStreamKey streamKey, KafkaCommitOffsetData kafkaTsk) {
        OffsetLifecycleTracker tracker;
        synchronized (commitDataLock) {
            tracker = partitionToOffsetLifecycleTrackerMap.get(kafkaTsk.getPartition());
        }
        if (tracker == null || tracker.consumerConnectionGeneration != kafkaTsk.getGeneration()) {
            log.atWarn().setMessage(()->"trafficKey's generation (" + kafkaTsk.getGeneration() + ") is not current (" +
                    (Optional.ofNullable(tracker).map(t->"new generation=" + t.consumerConnectionGeneration)
                            .orElse("Partition unassigned"))
                    + ").  Dropping this commit request since the record would " +
                    "have been handled again by a current consumer within this process or another. Full key=" +
                    kafkaTsk).log();
            return ITrafficCaptureSource.CommitResult.Ignored;
        }

        var p = kafkaTsk.getPartition();
        Optional<Long> newHeadValue;

        var k = new TopicPartition(topic, p);

        newHeadValue = tracker.removeAndReturnNewHead(kafkaTsk.getOffset());
        return newHeadValue.map(o -> {
            var v = new OffsetAndMetadata(o);
            log.atDebug().setMessage(()->"Adding new commit " + k + "->" + v + " to map").log();
            synchronized (commitDataLock) {
                addKeyContextForEventualCommit(streamKey, kafkaTsk, k);
                nextSetOfCommitsMap.put(k, v);
            }
            return ITrafficCaptureSource.CommitResult.AfterNextRead;
        }).orElseGet(() -> {
            synchronized (commitDataLock) {
                addKeyContextForEventualCommit(streamKey, kafkaTsk, k);
            }
            return ITrafficCaptureSource.CommitResult.BlockedByOtherCommits;
        });
    }

    private void addKeyContextForEventualCommit(ITrafficStreamKey streamKey, KafkaCommitOffsetData kafkaTsk, TopicPartition k) {
        nextSetOfKeysContextsBeingCommitted.computeIfAbsent(k, k2 -> new PriorityQueue<>())
                .add(new OrderedKeyHolder(kafkaTsk.getOffset(), streamKey));
    }

    private void safeCommit(IInstrumentationAttributes incomingContext) {
        HashMap<TopicPartition, OffsetAndMetadata> nextCommitsMapCopy;
        KafkaConsumerContexts.CommitScopeContext context = null;
        synchronized (commitDataLock) {
            if (nextSetOfCommitsMap.isEmpty()) {
                return;
            }
            context = new KafkaConsumerContexts.CommitScopeContext(incomingContext);
            nextCommitsMapCopy = new HashMap<>();
            nextCommitsMapCopy.putAll(nextSetOfCommitsMap);
        }
        try {
            safeCommitStatic(context, kafkaConsumer, nextCommitsMapCopy);
            synchronized (commitDataLock) {
                nextCommitsMapCopy.entrySet().stream()
                        .forEach(kvp->callbackUpTo(onCommitKeyCallback,
                                nextSetOfKeysContextsBeingCommitted.get(kvp.getKey()),
                                kvp.getValue().offset()));
                nextCommitsMapCopy.forEach((k,v)->nextSetOfCommitsMap.remove(k));
            }
            // This function will only ever be called in a threadsafe way, mutually exclusive from any
            // other call other than commitKafkaKey().  Since commitKafkaKey() doesn't alter
            // partitionToOffsetLifecycleTrackerMap, these lines can be outside of the commitDataLock mutex
            log.trace("partitionToOffsetLifecycleTrackerMap="+partitionToOffsetLifecycleTrackerMap);
            kafkaRecordsLeftToCommitEventually.set(partitionToOffsetLifecycleTrackerMap.values().stream()
                    .mapToInt(OffsetLifecycleTracker::size).sum());
            log.atDebug().setMessage(() -> "Done committing now records in flight=" +
                    kafkaRecordsLeftToCommitEventually.get()).log();
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
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void safeCommitStatic(KafkaConsumerContexts.CommitScopeContext context,
                                         Consumer<String, byte[]> kafkaConsumer,
                                         HashMap<TopicPartition, OffsetAndMetadata> nextCommitsMap) {
        assert !nextCommitsMap.isEmpty();
        log.atDebug().setMessage(() -> "Committing " + nextCommitsMap).log();
        try (var kafkaContext = new KafkaConsumerContexts.KafkaCommitScopeContext(context)) {
            kafkaConsumer.commitSync(nextCommitsMap);
        }
    }

    private static void callbackUpTo(java.util.function.Consumer<ITrafficStreamKey> onCommitKeyCallback,
                                     PriorityQueue<OrderedKeyHolder> orderedKeyHolders, long upToOffset) {
        for (var nextKeyHolder = orderedKeyHolders.peek();
             nextKeyHolder != null && nextKeyHolder.offset <= upToOffset;
             nextKeyHolder = orderedKeyHolders.peek())
        {
            onCommitKeyCallback.accept(nextKeyHolder.tsk);
            orderedKeyHolders.poll();
        }
    }

    String nextCommitsToString() {
        return "nextCommits="+nextSetOfCommitsMap.entrySet().stream()
                .map(kvp->kvp.getKey()+"->"+kvp.getValue()).collect(Collectors.joining(","));
    }
}
