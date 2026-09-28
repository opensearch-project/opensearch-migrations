package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.intake.RequestLifecycleInput;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/**
 * Encapsulates all Kafka dump-mode logic ({@code dump-raw}, {@code dump-http}, {@code dump-both}).
 *
 * <p>Reading here uses a plain {@link KafkaConsumer} with {@code assign}, no consumer group, an explicit
 * seek and no commit. Dumping needs no ownership and no commit authority, which is why it can run without
 * the Kafka source owner: it allocates one local partition generation per assigned partition and applies
 * records through replay intake's queue and owner thread.
 */
@Slf4j
public class KafkaTopicDumper {

    private long baseEpoch = -1;

    private long getBaseEpoch(TrafficStream ts) {
        if (baseEpoch < 0 && !ts.getSubStreamList().isEmpty()) {
            baseEpoch = ts.getSubStreamList().get(0).getTs().getSeconds();
        }
        return baseEpoch;
    }

    /**
     * Reads a topic and writes one line per record to stdout.
     *
     * <p>{@code observedPacketConnectionTimeout} and {@code packetTimeoutParamName} configure the
     * accumulator that {@code dump-http} builds, so only that mode reads them. They are threaded here so
     * the CLI options stay connected to the method that consumes them; do not remove them as unused.
     */
    @SuppressWarnings("java:S1172") // see javadoc: read by the dump-http path only
    public void runDumpFromKafka(
        String mode, String brokers, String topic, String authType,
        String kafkaUserName, String kafkaPassword, String propertyFile,
        Long startOffset, Long startTime, Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite,
        int observedPacketConnectionTimeout, String packetTimeoutParamName,
        RootReplayerContext rootContext
    ) throws Exception {
        var kafkaProps = KafkaConsumerProperties.buildKafkaProperties(
            brokers, "unused-dump-group", authType, kafkaUserName, kafkaPassword, propertyFile);
        kafkaProps.remove(ConsumerConfig.GROUP_ID_CONFIG);

        try (var consumer = new KafkaConsumer<String, byte[]>(kafkaProps)) {
            var partitions = consumer.partitionsFor(topic).stream()
                .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                .collect(Collectors.toList());
            consumer.assign(partitions);

            seekToStart(consumer, partitions, startOffset, startTime);
            var endOffsets = consumer.endOffsets(partitions);

            if ("dump-raw".equals(mode)) {
                runRawFromKafka(consumer, endOffsets, endOffset, endTime,
                    previewBytesRead, previewBytesWrite);
            } else {
                runHttpFromKafka(consumer, partitions, endOffsets, endOffset, endTime,
                    previewBytesRead, previewBytesWrite, "dump-both".equals(mode), rootContext);
            }
        }
    }

    private void seekToStart(KafkaConsumer<String, byte[]> consumer,
                             java.util.List<TopicPartition> partitions,
                             Long startOffset, Long startTime) {
        if (startOffset != null) {
            partitions.forEach(tp -> consumer.seek(tp, startOffset));
        } else if (startTime != null) {
            var timestampsToSearch = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> startTime * 1000));
            var offsets = consumer.offsetsForTimes(timestampsToSearch);
            offsets.forEach((tp, offsetAndTimestamp) -> {
                if (offsetAndTimestamp != null) {
                    consumer.seek(tp, offsetAndTimestamp.offset());
                } else {
                    consumer.seekToEnd(Collections.singleton(tp));
                }
            });
        } else {
            consumer.seekToBeginning(partitions);
        }
    }


    /**
     * Reconstructs HTTP transactions from a topic and prints one line each, which is the cheapest reading of
     * what source assembly produced.
     *
     * <p>The dumper is the bounded production construction path for G3's complete chain: it submits immutable
     * inputs through {@link ReplayIntakeInputQueue}, the real {@link ReplayIntakeOwner} applies them on its
     * owner thread, and {@link HttpTransactionDumper} consumes the resulting source-assembly messages. The
     * FIFO stop-after-draining marker proves every submitted batch was applied before this method returns.
     *
     * @param emitRaw also print the per-record raw line, which is what {@code dump-both} is
     */
    @SuppressWarnings("java:S1181") // Preserve Error while the intake owner is stopped and awaited.
    private void runHttpFromKafka(
        KafkaConsumer<String, byte[]> consumer,
        java.util.List<TopicPartition> partitions,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite,
        boolean emitRaw,
        RootReplayerContext rootContext
    ) throws Exception {
        var wakeupController = new WakeupController(consumer::wakeup, rootContext);
        var sourceInputs = new KafkaSourceInputQueue(
            wakeupController
        );
        var intakeInputs = new ReplayIntakeInputQueue();
        var dumper = new HttpTransactionDumper(
            System.out,
            "msg ",
            requestId -> submitRequired(
                intakeInputs,
                new RequestLifecycleInput.ConnectionRequestFinished(
                    requestId.connectionProcessingId().generation(),
                    requestId
                )
            )
        );
        var ownerFailure = new AtomicReference<Error>();
        var intake = createReplayIntake(
            consumer,
            emitRaw,
            previewBytesRead,
            previewBytesWrite,
            rootContext,
            dumper,
            sourceInputs,
            intakeInputs,
            ownerFailure
        );
        intake.start();

        Throwable primaryFailure = null;
        DumpBatchDemand batchDemand;
        try {
            batchDemand = runHttpDumpLoop(
                consumer,
                partitions,
                endOffsets,
                endOffset,
                endTime,
                dumper,
                wakeupController,
                sourceInputs,
                intakeInputs,
                ownerFailure
            );
            awaitSubmittedBatchesHandled(intakeInputs);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            stopAndAwaitReplayIntake(intakeInputs, intake, primaryFailure);
        }
        failOnDumpFailure(ownerFailure, sourceInputs, batchDemand);
    }

    private static void awaitSubmittedBatchesHandled(
        ReplayIntakeInputQueue intakeInputs
    ) throws Exception {
        intakeInputs.awaitPriorInputsHandled().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private ReplayIntakeOwner createReplayIntake(
        KafkaConsumer<String, byte[]> consumer,
        boolean emitRaw,
        int previewBytesRead,
        int previewBytesWrite,
        RootReplayerContext rootContext,
        HttpTransactionDumper dumper,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        AtomicReference<Error> ownerFailure
    ) {
        ReplayIntakeOwner.RecordObserver recordObserver = applicationRecord -> emitRawRecordIfRequested(
            emitRaw,
            applicationRecord,
            previewBytesRead,
            previewBytesWrite
        );
        return new ReplayIntakeOwner(
            intakeInputs,
            sourceInputs,
            dumper,
            failure -> {
                ownerFailure.compareAndSet(null, failure);
                consumer.wakeup();
            },
            rootContext.replayIntakeMetrics,
            recordObserver
        );
    }

    private void emitRawRecordIfRequested(
        boolean emitRaw,
        ApplicationKafkaRecord applicationRecord,
        int previewBytesRead,
        int previewBytesWrite
    ) {
        if (!emitRaw
            || applicationRecord.envelope().getPayloadCase() == CaptureRecord.PayloadCase.PAYLOAD_NOT_SET) {
            return;
        }
        System.out.println(TrafficStreamDumper.format(
            applicationRecord.envelope(),
            applicationRecord.recordId().generation().topicPartition().partition(),
            applicationRecord.recordId().offset(),
            previewBytesRead,
            previewBytesWrite,
            baseEpoch
        ));
    }

    private DumpBatchDemand runHttpDumpLoop(
        KafkaConsumer<String, byte[]> consumer,
        List<TopicPartition> partitions,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset,
        Long endTime,
        HttpTransactionDumper dumper,
        WakeupController wakeupController,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        AtomicReference<Error> ownerFailure
    ) {
        // One local generation per assigned partition, allocated once: a dump never rebalances, so a
        // partition is read by exactly one generation from start to end.
        var generations = initializeGenerations(partitions, intakeInputs);
        var batchDemand = new DumpBatchDemand(generations.values());
        var finishedPartitions = new HashSet<TopicPartition>();
        while (finishedPartitions.size() < endOffsets.size() && !isAtEnd(consumer, endOffsets)) {
            failOnDumpFailure(ownerFailure, sourceInputs, batchDemand);
            var polled = pollForDump(consumer, sourceInputs, wakeupController);
            failOnDumpFailure(ownerFailure, sourceInputs, batchDemand);
            var byPartition = collectHttpRecords(
                consumer,
                polled,
                endOffsets,
                endOffset,
                endTime,
                dumper,
                generations,
                finishedPartitions
            );
            submitHttpBatches(
                intakeInputs,
                generations,
                batchDemand,
                sourceInputs,
                ownerFailure,
                byPartition
            );
        }
        failOnDumpFailure(ownerFailure, sourceInputs, batchDemand);
        return batchDemand;
    }

    private static Map<TopicPartition, PartitionGenerationId> initializeGenerations(
        List<TopicPartition> partitions,
        ReplayIntakeInputQueue intakeInputs
    ) {
        var generations = new LinkedHashMap<TopicPartition, PartitionGenerationId>();
        for (var partition : partitions) {
            var generation = new PartitionGenerationId(partition, 0);
            generations.put(partition, generation);
            submitRequired(intakeInputs, new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        }
        return generations;
    }

    private Map<TopicPartition, List<ApplicationKafkaRecord>> collectHttpRecords(
        KafkaConsumer<String, byte[]> consumer,
        ConsumerRecords<String, byte[]> polled,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset,
        Long endTime,
        HttpTransactionDumper dumper,
        Map<TopicPartition, PartitionGenerationId> generations,
        Set<TopicPartition> finishedPartitions
    ) {
        var byPartition = new LinkedHashMap<TopicPartition, List<ApplicationKafkaRecord>>();
        for (var consumerRecord : polled) {
            var recordPartition = new TopicPartition(consumerRecord.topic(), consumerRecord.partition());
            if (!finishedPartitions.contains(recordPartition)) {
                if (pastEnd(consumerRecord, endOffset, endTime, endOffsets)) {
                    finishedPartitions.add(recordPartition);
                    consumer.pause(Set.of(recordPartition));
                } else {
                    addHttpRecord(byPartition, generations, recordPartition, consumerRecord, dumper);
                }
            }
        }
        return byPartition;
    }

    private void addHttpRecord(
        Map<TopicPartition, List<ApplicationKafkaRecord>> byPartition,
        Map<TopicPartition, PartitionGenerationId> generations,
        TopicPartition recordPartition,
        ConsumerRecord<String, byte[]> consumerRecord,
        HttpTransactionDumper dumper
    ) {
        var captureRecord = parseCaptureRecord(consumerRecord);
        if (captureRecord.hasTrafficStream()) {
            dumper.setBaseEpochSeconds(getBaseEpoch(captureRecord.getTrafficStream()));
        }
        byPartition.computeIfAbsent(recordPartition, ignored -> new ArrayList<>())
            .add(new ApplicationKafkaRecord(
                new KafkaRecordId(generations.get(recordPartition), consumerRecord.offset()),
                consumerRecord.timestamp(),
                consumerRecord.serializedValueSize() < 0 ? 0 : consumerRecord.serializedValueSize(),
                captureRecord
            ));
    }

    private static CaptureRecord parseCaptureRecord(ConsumerRecord<String, byte[]> consumerRecord) {
        try {
            return CaptureRecord.parseFrom(consumerRecord.value());
        } catch (InvalidProtocolBufferException e) {
            throw protocolViolation(consumerRecord, e);
        }
    }

    private static void submitHttpBatches(
        ReplayIntakeInputQueue intakeInputs,
        Map<TopicPartition, PartitionGenerationId> generations,
        DumpBatchDemand batchDemand,
        KafkaSourceInputQueue sourceInputs,
        AtomicReference<Error> ownerFailure,
        Map<TopicPartition, List<ApplicationKafkaRecord>> byPartition
    ) {
        for (var entry : byPartition.entrySet()) {
            var partition = entry.getKey();
            var generation = generations.get(partition);
            if (generation == null) {
                throw new IllegalStateException("no generation for " + partition);
            }
            submitRequired(
                intakeInputs,
                new ReplayIntakeInput.PartitionRecordBatch(
                    batchDemand.awaitNextRequestId(generation, sourceInputs, ownerFailure),
                    entry.getValue()
                )
            );
        }
    }

    /**
     * Adapts dump-mode polling to replay intake's real batch-demand protocol.
     *
     * <p>The first nonempty poll for a partition consumes its source-local bootstrap entitlement. Every
     * successor waits for the exact request identity emitted by replay intake; the dumper never synthesizes
     * an explicit identity or weakens intake's equality check. Requests for other partitions are retained
     * until that partition next has a nonempty batch.
     */
    static final class DumpBatchDemand {
        private static final long OWNER_FAILURE_CHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

        private final Set<PartitionGenerationId> bootstrapEntitlements = new HashSet<>();
        private final Map<PartitionGenerationId, PartitionBatchRequestId> explicitRequests =
            new LinkedHashMap<>();

        DumpBatchDemand(Iterable<PartitionGenerationId> generations) {
            generations.forEach(bootstrapEntitlements::add);
        }

        PartitionBatchRequestId awaitNextRequestId(
            PartitionGenerationId generation,
            KafkaSourceInputQueue sourceInputs,
            AtomicReference<Error> ownerFailure
        ) {
            if (bootstrapEntitlements.remove(generation)) {
                return new PartitionBatchRequestId(generation, 0);
            }
            while (true) {
                failOnDumpFailure(ownerFailure, sourceInputs, this);
                var requestId = explicitRequests.remove(generation);
                if (requestId != null) {
                    return requestId;
                }
                try {
                    sourceInputs.awaitInput(OWNER_FAILURE_CHECK_NANOS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "Interrupted while waiting for replay-intake batch demand for " + generation,
                        e
                    );
                }
            }
        }

        void drain(KafkaSourceInputQueue sourceInputs) {
            sourceInputs.drain().forEach(this::accept);
        }

        private void accept(KafkaSourceInput input) {
            switch (input) {
                case KafkaSourceInput.RequestNextPartitionBatch request -> {
                    var previous = explicitRequests.putIfAbsent(request.generation(), request.requestId());
                    if (previous != null) {
                        throw new IllegalStateException(
                            "Replay intake issued overlapping dump batch requests "
                                + previous + " and " + request.requestId()
                        );
                    }
                }
                case KafkaSourceInput.RecordProcessingFinished ignored -> {
                    // A dump has no commit authority. Completion is terminal bookkeeping to discard.
                }
                case KafkaSourceInput.CaptureProtocolViolationDetected violation ->
                    throw new CaptureRecordProtocolViolationException(
                        "Capture protocol violation at "
                            + violation.recordId() + ": " + violation.diagnostic()
                    );
                case KafkaSourceInput.GenerationCleanupFinished cleanup ->
                    throw new IllegalStateException(
                        "Replay intake unexpectedly cleaned dump generation " + cleanup.generation()
                    );
            }
        }
    }

    private static void stopAndAwaitReplayIntake(
        ReplayIntakeInputQueue intakeInputs,
        ReplayIntakeOwner intake,
        Throwable primaryFailure
    ) throws Exception {
        intakeInputs.requestStopAfterDraining();
        try {
            intake.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception shutdownFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(shutdownFailure);
            } else {
                throw shutdownFailure;
            }
        }
    }

    private static void failOnDumpFailure(
        AtomicReference<Error> ownerFailure,
        KafkaSourceInputQueue sourceInputs,
        DumpBatchDemand batchDemand
    ) {
        CaptureRecordProtocolViolationException protocolViolation = null;
        try {
            batchDemand.drain(sourceInputs);
        } catch (CaptureRecordProtocolViolationException violation) {
            protocolViolation = violation;
        }
        var failure = ownerFailure.get();
        if (protocolViolation != null) {
            if (failure != null) {
                protocolViolation.addSuppressed(failure);
            }
            throw protocolViolation;
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Polls at the same interruptible boundary as the replay source owner.
     *
     * <p>Replay intake reports protocol violations asynchronously through {@code sourceInputs}. Wiring that
     * queue to the consumer wakeup prevents the dump thread from reading or printing later records while a
     * violation is already waiting for it.
     */
    private static ConsumerRecords<String, byte[]> pollForDump(
        KafkaConsumer<String, byte[]> consumer,
        KafkaSourceInputQueue sourceInputs,
        WakeupController wakeupController
    ) {
        wakeupController.enterPoll(!sourceInputs.isEmpty());
        try {
            return consumer.poll(Duration.ofSeconds(2));
        } catch (WakeupException expectedQueueWakeup) {
            return ConsumerRecords.empty();
        } finally {
            wakeupController.leavePollAndConsumeWakeup();
        }
    }

    private static void submitRequired(ReplayIntakeInputQueue inputQueue, ReplayIntakeInput input) {
        if (!inputQueue.submit(input)) {
            throw new IllegalStateException(
                "Replay intake rejected " + input.getClass().getSimpleName() + " while the dump was active"
            );
        }
    }

    private void runRawFromKafka(
        KafkaConsumer<String, byte[]> consumer,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite
    ) {
        // Each partition reaches its bound on its own. Kafka defines no order across partitions, so one
        // partition crossing its snapshot end or the requested --end-offset/--end-time says nothing about
        // whether another still has records to dump. Retiring the partition and pausing it is what keeps the
        // rest of the dump running while still terminating.
        var finishedPartitions = new HashSet<TopicPartition>();
        while (finishedPartitions.size() < endOffsets.size() && !isAtEnd(consumer, endOffsets)) {
            var polled = consumer.poll(Duration.ofSeconds(2));
            for (var consumerRecord : polled) {
                var recordPartition = new TopicPartition(consumerRecord.topic(), consumerRecord.partition());
                if (!finishedPartitions.contains(recordPartition)) {
                    processRawRecord(
                        consumer,
                        consumerRecord,
                        recordPartition,
                        endOffsets,
                        endOffset,
                        endTime,
                        previewBytesRead,
                        previewBytesWrite,
                        finishedPartitions
                    );
                }
            }
        }
    }

    private void processRawRecord(
        KafkaConsumer<String, byte[]> consumer,
        ConsumerRecord<String, byte[]> consumerRecord,
        TopicPartition recordPartition,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset,
        Long endTime,
        int previewBytesRead,
        int previewBytesWrite,
        Set<TopicPartition> finishedPartitions
    ) {
        if (pastEnd(consumerRecord, endOffset, endTime, endOffsets)) {
            finishedPartitions.add(recordPartition);
            consumer.pause(Set.of(recordPartition));
            return;
        }
        var captureRecord = parseCaptureRecord(consumerRecord);
        if (captureRecord.hasTrafficStream()) {
            getBaseEpoch(captureRecord.getTrafficStream());
        }
        System.out.println(TrafficStreamDumper.format(
            captureRecord,
            consumerRecord.partition(),
            consumerRecord.offset(),
            previewBytesRead,
            previewBytesWrite,
            baseEpoch
        ));
    }

    /**
     * The dump loop terminates only when every assigned partition's current
     * position has reached the endOffset snapshot taken at startup. Using
     * !poll.isEmpty() as the exit condition (the prior approach) raced the
     * consumer's first metadata round-trip — on a fresh `assign()` the very
     * first poll often returns just one warm-up record and the second poll
     * comes back empty before the prefetch kicks in, exiting after a single
     * record. Driving termination off position vs. endOffsets is what the
     * Kafka client API gives us for "I've drained the snapshot I asked for"
     * and is robust to empty intermediate polls.
     */
    private static boolean isAtEnd(KafkaConsumer<String, byte[]> consumer,
                                   Map<TopicPartition, Long> endOffsets) {
        for (var entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static CaptureRecordProtocolViolationException protocolViolation(
        ConsumerRecord<String, byte[]> consumerRecord,
        InvalidProtocolBufferException cause
    ) {
        return new CaptureRecordProtocolViolationException(
            "Kafka record at "
                + consumerRecord.topic()
                + "-"
                + consumerRecord.partition()
                + "@"
                + consumerRecord.offset()
                + " is not a CaptureRecord envelope",
            cause
        );
    }

    private static boolean pastEnd(ConsumerRecord<String, byte[]> rec,
                                   Long endOffset, Long endTime,
                                   Map<TopicPartition, Long> endOffsets) {
        if (endOffset != null && rec.offset() > endOffset) return true;
        if (endTime != null && rec.timestamp() > endTime * 1000) return true;
        var tp = new TopicPartition(rec.topic(), rec.partition());
        return rec.offset() >= endOffsets.getOrDefault(tp, Long.MAX_VALUE);
    }
}
