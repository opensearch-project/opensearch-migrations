package org.opensearch.migrations.replay.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.opensearch.migrations.Utils;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.KafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;

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
import org.slf4j.event.Level;

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
        @Getter
        final long offset;
        @Getter
        @NonNull
        final ITrafficStreamKey tsk;

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

    @NonNull
    private final RootReplayerContext globalContext;
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
    // Heartbeat counters — reset each heartbeat cycle
    private final AtomicInteger pollsSinceLastHeartbeat = new AtomicInteger();
    private final AtomicInteger emptyPollsSinceLastHeartbeat = new AtomicInteger();
    private final AtomicInteger commitsSinceLastHeartbeat = new AtomicInteger();
    private static final org.slf4j.Logger heartbeatLogger =
        org.slf4j.LoggerFactory.getLogger("KafkaHeartbeat");
    /** Called with revoked partition numbers so the source layer can synthesize interrupted-close
     *  events for any active connections on them. Always invoked at the OLD generation (before
     *  any subsequent onPartitionsAssigned bumps it), which matches the session.generation that
     *  was stamped on those channels — required for onNetworkConnectionClosed to find and clear
     *  the pendingTrafficSourceReaderInterruptedCloses entries. */
    private java.util.function.Consumer<Collection<Integer>> onPartitionsTrulyLostCallback = ignored -> {};
    /** Partitions revoked or lost during the in-flight {@code poll()}, populated by
     *  {@link #cleanupRevokedPartitions}. Cleared at the top of each poll. The post-poll
     *  recovery in {@link #safePollWithSwallowedRuntimeExceptions} drops records from these
     *  partitions and resets them to their last committed offset; partitions never in this
     *  set keep their polled records and position. */
    private final Set<TopicPartition> partitionsTouchedDuringPoll =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    public TrackingKafkaConsumer(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        String topic,
        Duration keepAliveInterval,
        Clock c,
        java.util.function.Consumer<ITrafficStreamKey> onCommitKeyCallback
    ) {
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

    public int getConsumerConnectionGeneration() {
        return consumerConnectionGeneration.get();
    }

    public void setOnPartitionsTrulyLostCallback(java.util.function.Consumer<Collection<Integer>> callback) {
        this.onPartitionsTrulyLostCallback = callback;
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        // Fence/timeout: commits are impossible. Same cleanup as a revocation, just no commit attempt.
        cleanupRevokedPartitions(partitions, /*attemptCommit=*/ false);
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        cleanupRevokedPartitions(partitions, /*attemptCommit=*/ true);
    }

    /**
     * Tear down per-partition state and fire {@link #onPartitionsTrulyLostCallback} at the OLD
     * generation (before any subsequent onPartitionsAssigned bumps it), so synthesized close
     * events match the {@code session.generation} stamped on channels opened during this gen.
     * The truly-lost callback runs OUTSIDE {@code commitDataLock} so it can freely touch the
     * source's concurrent collections.
     */
    private void cleanupRevokedPartitions(Collection<TopicPartition> partitions, boolean attemptCommit) {
        if (partitions.isEmpty()) {
            log.atDebug().setMessage("{} revoked/lost no partitions.").addArgument(this).log();
            return;
        }
        // If we're inside poll(), tag these so the post-poll recovery drops their stale-buffered
        // records and resets ONLY them. Untouched partitions keep their records and position.
        partitionsTouchedDuringPoll.addAll(partitions);
        new KafkaConsumerContexts.AsyncListeningContext(globalContext).onPartitionsRevoked(partitions);
        var partitionNums = new ArrayList<Integer>(partitions.size());
        synchronized (commitDataLock) {
            if (attemptCommit) {
                safeCommit(globalContext::createCommitContext);
            }
            partitions.forEach(p -> {
                var tp = new TopicPartition(topic, p.partition());
                nextSetOfCommitsMap.remove(tp);
                nextSetOfKeysContextsBeingCommitted.remove(tp);
                partitionToOffsetLifecycleTrackerMap.remove(p.partition());
                partitionNums.add(p.partition());
            });
            kafkaRecordsLeftToCommitEventually.set(
                partitionToOffsetLifecycleTrackerMap.values().stream().mapToInt(OffsetLifecycleTracker::size).sum()
            );
            kafkaRecordsReadyToCommit.set(!nextSetOfCommitsMap.values().isEmpty());
            log.atWarn().setMessage("{} partitions {} for {}")
                .addArgument(this)
                .addArgument(attemptCommit ? "revoked" : "lost (no commit attempted)")
                .addArgument(() -> partitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
        onPartitionsTrulyLostCallback.accept(partitionNums);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> newPartitions) {
        if (newPartitions.isEmpty()) {
            log.atInfo().setMessage("{} assigned no new partitions.").addArgument(this).log();
            return;
        }
        // We deliberately do NOT touch partitionsTouchedDuringPoll here. A pure assignment
        // doesn't invalidate any pre-rebalance buffer; a round-trip is already tagged on the
        // revoke side.
        new KafkaConsumerContexts.AsyncListeningContext(globalContext).onPartitionsAssigned(newPartitions);
        synchronized (commitDataLock) {
            consumerConnectionGeneration.incrementAndGet();
            newPartitions.forEach(
                p -> partitionToOffsetLifecycleTrackerMap.computeIfAbsent(
                    p.partition(),
                    x -> new OffsetLifecycleTracker(consumerConnectionGeneration.get())
                )
            );
            log.atInfo()
                .setMessage("{} partitions added for {}")
                .addArgument(this)
                .addArgument(() -> newPartitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
    }

    public void close() {
        log.atInfo()
            .setMessage("Kafka consumer closing.  Committing (implicitly by Kafka's consumer): {}")
            .addArgument(this::nextCommitsToString)
            .log();
        kafkaConsumer.close();
    }

    public Optional<Instant> getNextRequiredTouch() {
        var lastTouchTime = lastTouchTimeRef.get();
        Optional<Instant> r;
        if (kafkaRecordsLeftToCommitEventually.get() == 0) {
            r = Optional.empty();
        }
        else {
            r = Optional.of(kafkaRecordsReadyToCommit.get() ? Instant.now() : lastTouchTime.plus(keepAliveInterval));
        }
        log.atTrace().setMessage("returning next required touch at {} from a lastTouchTime of {}")
            .addArgument(() -> r.map(Instant::toString).orElse("N/A"))
            .addArgument(lastTouchTime)
            .log();
        return r;
    }

    public void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {
        try (var touchCtx = context.createNewTouchContext()) {
            log.trace("touch() called.");
            pause();
            try (var pollCtx = touchCtx.createNewPollContext()) {
                var records = kafkaConsumer.poll(Duration.ZERO);
                if (!records.isEmpty()) {
                    throw new IllegalStateException(
                        "Expected no entries once the consumer was paused.  "
                            + "This may have happened because a new assignment slipped into the consumer AFTER pause calls."
                    );
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                log.atWarn().setCause(e)
                    .setMessage("Unable to poll the topic: {} with our Kafka consumer ({}). "
                            + "Swallowing and awaiting next metadata refresh to try again.")
                    .addArgument(topic)
                    .addArgument(this)
                    .log();
            } finally {
                resume();
            }
            safeCommit(context::createCommitContext);
            lastTouchTimeRef.set(clock.instant());
        }
    }

    private void pause() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.pause(activePartitions);
        } catch (IllegalStateException e) {
            log.atError()
                .setCause(e)
                .setMessage(
                    () -> "Unable to pause the topic partitions: {}.  "
                        + "The active partitions passed here : {}.  "
                        + "The active partitions as tracked here are: {}.  "
                        + "The active partitions according to the consumer: {}")
                .addArgument(topic)
                .addArgument(() -> activePartitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> getActivePartitions().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> kafkaConsumer.assignment().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
    }

    private void resume() {
        var activePartitions = kafkaConsumer.assignment();
        try {
            kafkaConsumer.resume(activePartitions);
        } catch (IllegalStateException e) {
            log.atError()
                .setCause(e)
                .setMessage("Unable to resume the topic partitions: {}.  "
                        + "This may not be a fatal error for the entire process as the consumer should eventually " +
                        " rejoin and rebalance.  "
                        + "The active partitions passed here : {}.  "
                        + "The active partitions as tracked here are: {}.  "
                        + "The active partitions according to the consumer: {}"
                )
                .addArgument(topic)
                .addArgument(() -> activePartitions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> getActivePartitions().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addArgument(() -> kafkaConsumer.assignment().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .log();
        }
    }

    private Collection<TopicPartition> getActivePartitions() {
        return partitionToOffsetLifecycleTrackerMap.keySet()
            .stream()
            .map(p -> new TopicPartition(topic, p))
            .collect(Collectors.toList());
    }

    public <T> Stream<T> getNextBatchOfRecords(
        ITrafficSourceContexts.IReadChunkContext context,
        BiFunction<KafkaCommitOffsetData, ConsumerRecord<String, byte[]>, T> builder
    ) {
        safeCommit(context::createCommitContext);
        var records = safePollWithSwallowedRuntimeExceptions(context);
        safeCommit(context::createCommitContext);
        return applyBuilder(builder, records);
    }

    private <T> Stream<T> applyBuilder(
        BiFunction<KafkaCommitOffsetData, ConsumerRecord<String, byte[]>, T> builder,
        ConsumerRecords<String, byte[]> records
    ) {
        return StreamSupport.stream(records.spliterator(), false).map(kafkaRecord -> {
            var offsetTracker = partitionToOffsetLifecycleTrackerMap.get(kafkaRecord.partition());
            var offsetDetails = new PojoKafkaCommitOffsetData(
                offsetTracker.consumerConnectionGeneration,
                kafkaRecord.partition(),
                kafkaRecord.offset()
            );
            offsetTracker.add(offsetDetails.getOffset(), kafkaRecord.key());
            kafkaRecordsLeftToCommitEventually.incrementAndGet();
            log.atTrace().setMessage("records in flight={}").addArgument(kafkaRecordsLeftToCommitEventually::get).log();
            return builder.apply(offsetDetails, kafkaRecord);
        });
    }

    private ConsumerRecords<String, byte[]> safePollWithSwallowedRuntimeExceptions(
        ITrafficSourceContexts.IReadChunkContext context
    ) {
        try {
            lastTouchTimeRef.set(clock.instant());
            partitionsTouchedDuringPoll.clear();
            ConsumerRecords<String, byte[]> records;
            try (var pollContext = context.createPollContext()) {
                records = kafkaConsumer.poll(keepAliveInterval.dividedBy(POLL_TIMEOUT_KEEP_ALIVE_DIVISOR));
            }
            // If a rebalance fired inline, recover only the partitions it touched.
            var touched = new HashSet<>(partitionsTouchedDuringPoll);
            partitionsTouchedDuringPoll.clear();
            if (!touched.isEmpty()) {
                records = recoverFromInlineRebalance(records, touched);
            }
            pollsSinceLastHeartbeat.incrementAndGet();
            if (records.isEmpty()) {
                emptyPollsSinceLastHeartbeat.incrementAndGet();
            }
            log.atLevel(records.isEmpty() ? Level.TRACE : Level.DEBUG)
                .setMessage("Kafka consumer poll has fetched {} records.  Records in flight={}")
                .addArgument(records::count)
                .addArgument(kafkaRecordsLeftToCommitEventually::get)
                .log();
            log.atTrace().setMessage("All positions: {{}}")
                .addArgument(() -> kafkaConsumer.assignment().stream()
                    .map(tp -> tp + ": " + kafkaConsumer.position(tp))
                    .collect(Collectors.joining(",")))
                .log();
            log.atTrace().setMessage("All previously COMMITTED positions: {{}}")
                .addArgument(() -> kafkaConsumer.assignment().stream()
                    .map(tp -> tp + ": " + kafkaConsumer.committed(Set.of(tp)))
                    .collect(Collectors.joining(",")))
                .log();
            return records;
        } catch (RuntimeException e) {
            log.atWarn().setCause(e)
                .setMessage("Unable to poll the topic: {} with our Kafka consumer ({}). "
                        + "Swallowing and awaiting next metadata refresh to try again.")
                .addArgument(topic)
                .addArgument(this)
                .log();
            return new ConsumerRecords<>(Collections.emptyMap(), Collections.emptyMap());
        }
    }

    /**
     * Inline rebalance recovery: drop polled records from partitions the rebalance touched
     * (their pre-rebalance position is no longer valid), keep records from untouched assigned
     * partitions (their position is still consistent with what we polled), and reset each
     * touched partition still in our assignment to its last committed offset (or the beginning
     * if no commit). Partitions that were touched and are no longer assigned are dropped silently
     * — they're not ours anymore. Untouched partitions are left alone, which is essential when
     * one partition rebalances while another has many records in flight past its last commit.
     */
    private ConsumerRecords<String, byte[]> recoverFromInlineRebalance(
        ConsumerRecords<String, byte[]> polled,
        Set<TopicPartition> touched
    ) {
        var assigned = kafkaConsumer.assignment();
        var resetSet = touched.stream().filter(assigned::contains).collect(Collectors.toSet());
        // Keep records only from partitions that are still assigned AND were not touched.
        var kept = new HashMap<TopicPartition, java.util.List<ConsumerRecord<String, byte[]>>>();
        for (var rec : polled) {
            var tp = new TopicPartition(rec.topic(), rec.partition());
            if (assigned.contains(tp) && !touched.contains(tp)) {
                kept.computeIfAbsent(tp, k -> new ArrayList<>()).add(rec);
            }
        }
        // Reset touched-and-still-assigned partitions to last-committed (or beginning if none).
        // committed() is a broker RPC, paid only on this rare recovery path.
        if (!resetSet.isEmpty()) {
            var committedMap = kafkaConsumer.committed(resetSet);
            var rewindToBeginning = new ArrayList<TopicPartition>();
            resetSet.forEach(tp -> {
                var c = committedMap.get(tp);
                if (c != null) kafkaConsumer.seek(tp, c.offset());
                else rewindToBeginning.add(tp);
            });
            if (!rewindToBeginning.isEmpty()) {
                kafkaConsumer.seekToBeginning(rewindToBeginning);
            }
        }
        log.atInfo().setMessage("Rebalance during poll: kept {} record(s) from untouched partitions; " +
                "reset {} partition(s); dropped polled records on touched partitions ({} total polled)")
            .addArgument(() -> kept.values().stream().mapToInt(java.util.List::size).sum())
            .addArgument(resetSet::size)
            .addArgument(polled::count)
            .log();
        return new ConsumerRecords<>(kept, Collections.emptyMap());
    }

    ITrafficCaptureSource.CommitResult commitKafkaKey(ITrafficStreamKey streamKey, KafkaCommitOffsetData kafkaTsk) {
        OffsetLifecycleTracker tracker;
        synchronized (commitDataLock) {
            tracker = partitionToOffsetLifecycleTrackerMap.get(kafkaTsk.getPartition());
        }
        if (tracker == null || tracker.consumerConnectionGeneration != kafkaTsk.getGeneration()) {
            log.atWarn()
                .setMessage(
                    () -> "trafficKey's generation ({}) is not current ({})." +
                        "  Dropping this commit request since the record would "
                        + "have been handled again by a current consumer within this process or another. Full key={}")
                .addArgument(kafkaTsk::getGeneration)
                .addArgument((Optional.ofNullable(tracker)
                    .map(t -> "new generation=" + t.consumerConnectionGeneration)
                    .orElse("Partition unassigned")))
                .addArgument(kafkaTsk)
                .log();
            return ITrafficCaptureSource.CommitResult.IGNORED;
        }

        var p = kafkaTsk.getPartition();
        Optional<Long> newHeadValue;

        var k = new TopicPartition(topic, p);

        newHeadValue = tracker.removeAndReturnNewHead(kafkaTsk.getOffset());
        return newHeadValue.map(o -> {
            var v = new OffsetAndMetadata(o);
            log.atDebug().setMessage("Adding new commit {}->{} to map").addArgument(k).addArgument(v).log();
            synchronized (commitDataLock) {
                addKeyContextForEventualCommit(streamKey, kafkaTsk, k);
                nextSetOfCommitsMap.put(k, v);
            }
            kafkaRecordsReadyToCommit.set(true);
            return ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ;
        }).orElseGet(() -> {
            synchronized (commitDataLock) {
                addKeyContextForEventualCommit(streamKey, kafkaTsk, k);
            }
            return ITrafficCaptureSource.CommitResult.BLOCKED_BY_OTHER_COMMITS;
        });
    }

    private void addKeyContextForEventualCommit(
        ITrafficStreamKey streamKey,
        KafkaCommitOffsetData kafkaTsk,
        TopicPartition k
    ) {
        nextSetOfKeysContextsBeingCommitted.computeIfAbsent(k, k2 -> new PriorityQueue<>())
            .add(new OrderedKeyHolder(kafkaTsk.getOffset(), streamKey));
    }

    private void safeCommit(Supplier<IKafkaConsumerContexts.ICommitScopeContext> commitContextSupplier) {
        HashMap<TopicPartition, OffsetAndMetadata> nextCommitsMapCopy;
        IKafkaConsumerContexts.ICommitScopeContext context = null;
        synchronized (commitDataLock) {
            if (nextSetOfCommitsMap.isEmpty()) {
                return;
            }
            context = commitContextSupplier.get();
            nextCommitsMapCopy = new HashMap<>(nextSetOfCommitsMap);
        }
        try {
            safeCommitStatic(context, kafkaConsumer, nextCommitsMapCopy);
            commitsSinceLastHeartbeat.incrementAndGet();
            synchronized (commitDataLock) {
                nextCommitsMapCopy.entrySet()
                    .stream()
                    .forEach(
                        kvp -> callbackUpTo(
                            onCommitKeyCallback,
                            nextSetOfKeysContextsBeingCommitted.get(kvp.getKey()),
                            kvp.getValue().offset()
                        )
                    );
                nextCommitsMapCopy.forEach((k, v) -> nextSetOfCommitsMap.remove(k));
            }
            // This function will only ever be called in a threadsafe way, mutually exclusive from any
            // other call other than commitKafkaKey(). Since commitKafkaKey() doesn't alter
            // partitionToOffsetLifecycleTrackerMap, these lines can be outside of the commitDataLock mutex
            log.atTrace().setMessage("partitionToOffsetLifecycleTrackerMap={}").addArgument(partitionToOffsetLifecycleTrackerMap).log();
            kafkaRecordsLeftToCommitEventually.set(
                partitionToOffsetLifecycleTrackerMap.values().stream().mapToInt(OffsetLifecycleTracker::size).sum()
            );
            log.atDebug().setMessage("Done committing now records in flight={}")
                .addArgument(kafkaRecordsLeftToCommitEventually::get)
                .log();
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Error while committing.  "
                        + "Another consumer may already be processing messages before these commits.  "
                        + "Commits ARE NOT being discarded here, with the expectation that the revoked callback "
                        + "(onPartitionsRevoked) will be called.  "
                        + "Within that method, commits for unassigned partitions will be discarded.  "
                        + "After that, touch() or poll() will trigger another commit attempt."
                        + "Those calls will occur in the near future if assigned partitions have pending commits.{}"
                )
                .addArgument(() -> nextSetOfCommitsMap.entrySet()
                    .stream()
                    .map(kvp -> kvp.getKey() + "->" + kvp.getValue())
                    .collect(Collectors.joining(",")))
                .log();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void safeCommitStatic(
        IKafkaConsumerContexts.ICommitScopeContext context,
        Consumer<String, byte[]> kafkaConsumer,
        HashMap<TopicPartition, OffsetAndMetadata> nextCommitsMap
    ) {
        assert !nextCommitsMap.isEmpty();
        log.atDebug().setMessage("Committing {}").addArgument(nextCommitsMap).log();
        try (var kafkaContext = context.createNewKafkaCommitContext()) {
            kafkaConsumer.commitSync(nextCommitsMap);
        }
    }

    private static void callbackUpTo(
        java.util.function.Consumer<ITrafficStreamKey> onCommitKeyCallback,
        PriorityQueue<OrderedKeyHolder> orderedKeyHolders,
        long upToOffset
    ) {
        for (var nextKeyHolder = orderedKeyHolders.peek(); nextKeyHolder != null
            && nextKeyHolder.offset <= upToOffset; nextKeyHolder = orderedKeyHolders.peek()) {
            onCommitKeyCallback.accept(nextKeyHolder.tsk);
            orderedKeyHolders.poll();
        }
    }

    /** Emit a periodic heartbeat log summarizing the Kafka consumer state. */
    public void logHeartbeat() {
        int polls = pollsSinceLastHeartbeat.getAndSet(0);
        int emptyPolls = emptyPollsSinceLastHeartbeat.getAndSet(0);
        int commits = commitsSinceLastHeartbeat.getAndSet(0);
        int inflight = kafkaRecordsLeftToCommitEventually.get();
        boolean readyToCommit = kafkaRecordsReadyToCommit.get();
        int generation = consumerConnectionGeneration.get();

        synchronized (commitDataLock) {
            var sb = new StringBuilder();
            sb.append("generation=").append(generation);
            sb.append(" partitions=").append(partitionToOffsetLifecycleTrackerMap.keySet());
            sb.append(" inflight=").append(inflight);

            // Report commit head details from the first partition's tracker
            partitionToOffsetLifecycleTrackerMap.values().stream().findFirst().ifPresent(tracker -> {
                tracker.peekHeadOffset().ifPresent(headOffset -> {
                    sb.append(" commitHead={offset=").append(headOffset);
                    tracker.peekHeadMetadata().ifPresent(meta -> {
                        sb.append(", conn=").append(meta.connectionId);
                        var age = Duration.between(meta.addedAt, clock.instant());
                        sb.append(", age=").append(Utils.formatDurationInSeconds(age));
                    });
                    sb.append("}");
                });
                sb.append(" commitTail=").append(tracker.getHighWatermark());
                sb.append(" queueSize=").append(tracker.size());
            });

            sb.append(" polls=").append(polls);
            sb.append(" emptyPolls=").append(emptyPolls);
            sb.append(" commits=").append(commits);
            sb.append(" readyToCommit=").append(readyToCommit);
            sb.append(" pendingCommitPartitions=").append(nextSetOfCommitsMap.size());

            heartbeatLogger.atInfo().setMessage("{}").addArgument(sb).log();
        }
    }


    String nextCommitsToString() {
        return "nextCommits="
            + nextSetOfCommitsMap.entrySet()
                .stream()
                .map(kvp -> kvp.getKey() + "->" + kvp.getValue())
                .collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        synchronized (commitDataLock) {
            int partitionCount = partitionToOffsetLifecycleTrackerMap.size();
            int commitsPending = nextSetOfCommitsMap.size();
            int recordsLeftToCommit = kafkaRecordsLeftToCommitEventually.get();
            boolean recordsReadyToCommit = kafkaRecordsReadyToCommit.get();
            return String.format(
                "TrackingKafkaConsumer{topic='%s', partitionCount=%d, commitsPending=%d, "
                    + "recordsLeftToCommit=%d, recordsReadyToCommit=%b}",
                topic,
                partitionCount,
                commitsPending,
                recordsLeftToCommit,
                recordsReadyToCommit
            );
        }
    }
}
