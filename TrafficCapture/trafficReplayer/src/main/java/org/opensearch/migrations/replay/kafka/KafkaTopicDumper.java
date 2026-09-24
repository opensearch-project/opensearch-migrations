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
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
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

import io.opentelemetry.api.OpenTelemetry;

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
        int observedPacketConnectionTimeout, String packetTimeoutParamName
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
                    previewBytesRead, previewBytesWrite, "dump-both".equals(mode));
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
    private void runHttpFromKafka(
        KafkaConsumer<String, byte[]> consumer,
        java.util.List<TopicPartition> partitions,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite,
        boolean emitRaw
    ) throws Exception {
        var dumper = new HttpTransactionDumper(System.out, "msg ");
        var rootContext = new RootReplayerContext(OpenTelemetry.noop());
        var wakeupController = new WakeupController(consumer::wakeup, rootContext);
        var sourceInputs = new KafkaSourceInputQueue(
            wakeupController
        );
        var intakeInputs = new ReplayIntakeInputQueue();
        var intake = new ReplayIntakeOwner(
            intakeInputs,
            sourceInputs,
            dumper,
            failure -> log.error("Replay intake failed while dumping", failure),
            rootContext.replayIntakeMetrics
        );
        intake.start();

        try {
            // One local generation per assigned partition, allocated once: a dump never rebalances, so a
            // partition is read by exactly one generation from start to end.
            var generations = new LinkedHashMap<TopicPartition, PartitionGenerationId>();
            for (var partition : partitions) {
                var generation = new PartitionGenerationId(partition, 0);
                generations.put(partition, generation);
                submitRequired(intakeInputs, new ReplayIntakeInput.PartitionGenerationAssigned(generation));
            }

            var batchSequence = 0L;
            var finishedPartitions = new HashSet<TopicPartition>();
            while (finishedPartitions.size() < endOffsets.size() && !isAtEnd(consumer, endOffsets)) {
                failOnProtocolViolation(sourceInputs);
                var polled = pollForDump(consumer, sourceInputs, wakeupController);
                failOnProtocolViolation(sourceInputs);
                var byPartition = new LinkedHashMap<TopicPartition, List<ApplicationKafkaRecord>>();
                for (var rec : polled) {
                    var recordPartition = new TopicPartition(rec.topic(), rec.partition());
                    if (finishedPartitions.contains(recordPartition)) {
                        continue;
                    }
                    if (pastEnd(rec, endOffset, endTime, endOffsets)) {
                        finishedPartitions.add(recordPartition);
                        consumer.pause(Set.of(recordPartition));
                        continue;
                    }
                    CaptureRecord captureRecord;
                    try {
                        captureRecord = CaptureRecord.parseFrom(rec.value());
                    } catch (InvalidProtocolBufferException e) {
                        throw protocolViolation(rec, e);
                    }
                    if (captureRecord.hasTrafficStream()) {
                        getBaseEpoch(captureRecord.getTrafficStream());
                    }
                    if (emitRaw) {
                        System.out.println(TrafficStreamDumper.format(
                            captureRecord, rec.partition(), rec.offset(),
                            previewBytesRead, previewBytesWrite, baseEpoch));
                    }
                    byPartition.computeIfAbsent(recordPartition, ignored -> new ArrayList<>())
                        .add(new ApplicationKafkaRecord(
                            new KafkaRecordId(generations.get(recordPartition), rec.offset()),
                            rec.timestamp(),
                            rec.serializedValueSize() < 0 ? 0 : rec.serializedValueSize(),
                            captureRecord
                        ));
                }
                batchSequence++;
                for (var entry : byPartition.entrySet()) {
                    submitRequired(
                        intakeInputs,
                        new ReplayIntakeInput.PartitionRecordBatch(
                            new PartitionBatchRequestId(generations.get(entry.getKey()), batchSequence),
                            entry.getValue()
                        )
                    );
                }
            }
        } finally {
            intakeInputs.requestStopAfterDraining();
            intake.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        failOnProtocolViolation(sourceInputs);
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

    /**
     * Fails a dump on anything intake reported as invalid capture input and discards record completions.
     *
     * <p>Record completions are discarded deliberately: a dump holds no commit authority, so completion is the
     * one message it has nothing to do with. Draining rather than ignoring matters because the queue would
     * otherwise grow by one entry per record for the length of the topic.
     */
    private static void failOnProtocolViolation(KafkaSourceInputQueue sourceInputs) {
        for (var input : sourceInputs.drain()) {
            if (input instanceof KafkaSourceInput.CaptureProtocolViolationDetected violation) {
                throw new CaptureRecordProtocolViolationException(
                    "Capture protocol violation at " + violation.recordId() + ": " + violation.diagnostic()
                );
            }
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
            for (var rec : polled) {
                var recordPartition = new TopicPartition(rec.topic(), rec.partition());
                if (finishedPartitions.contains(recordPartition)) {
                    continue;
                }
                if (pastEnd(rec, endOffset, endTime, endOffsets)) {
                    finishedPartitions.add(recordPartition);
                    consumer.pause(Set.of(recordPartition));
                    continue;
                }
                try {
                    var captureRecord = CaptureRecord.parseFrom(rec.value());
                    if (captureRecord.hasTrafficStream()) {
                        getBaseEpoch(captureRecord.getTrafficStream());
                    }
                    System.out.println(TrafficStreamDumper.format(
                        captureRecord,
                        rec.partition(),
                        rec.offset(),
                        previewBytesRead,
                        previewBytesWrite,
                        baseEpoch
                    ));
                } catch (InvalidProtocolBufferException e) {
                    throw protocolViolation(rec, e);
                }
            }
        }
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
        ConsumerRecord<String, byte[]> record,
        InvalidProtocolBufferException cause
    ) {
        return new CaptureRecordProtocolViolationException(
            "Kafka record at "
                + record.topic()
                + "-"
                + record.partition()
                + "@"
                + record.offset()
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
