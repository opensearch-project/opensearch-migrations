package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

// REBUILD-LIMBO-START(G3)
// Imports used only by the deferred HTTP-reconstruction members below. They come back with those
// members in G3; see docs/replayerRebuildPlanA-inPlace.md G3.
/*

import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;

import org.apache.kafka.clients.consumer.ConsumerRecords;

*/
// REBUILD-LIMBO-END(G3)
/**
 * Encapsulates all dump-mode logic (dump-raw, dump-http, dump-both) for both
 * Kafka and file-based sources. Keeps Kafka-specific details out of TrafficReplayer.
 *
 * <p>The file source is still deferred: it needs {@code ISimpleTrafficCaptureSource}, which is G5's.
 *
 * <p>Reading here uses a plain {@link KafkaConsumer} with {@code assign}, no consumer group, an explicit
 * seek and no commit. Dumping needs no ownership and no commit authority, which is why it can run without
 * the Kafka source owner: it allocates one local partition generation per assigned partition and applies
 * records to replay intake directly.
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

// REBUILD-LIMBO-START(G3)
// runDumpFromSource -- the file-input dump path. Blocked on ISimpleTrafficCaptureSource and
// ITrafficStreamWithKey (G5's source abstraction) for every mode, and additionally on the accumulator
// for the non-raw modes. Deferred to G3 with the rest of HTTP reconstruction; the open question of
// whether the file source speaks bare base64 TrafficStream or a CaptureRecord envelope is settled
// there, and is listed in the deferral ledger in docs/replayerRebuildStatus.md.
/*
    @SuppressWarnings("java:S3776")
    public void runDumpFromSource(
        String mode, ISimpleTrafficCaptureSource source,
        int previewBytesRead, int previewBytesWrite,
        int observedPacketConnectionTimeout, String packetTimeoutParamName,
        RootReplayerContext topContext
    ) throws Exception {
        if ("dump-raw".equals(mode)) {
            while (true) {
                try {
                    var chunks = source.readNextTrafficStreamChunk(topContext::createReadChunkContext).get();
                    for (var sourceInput : chunks) {
                        if (!(sourceInput instanceof ITrafficStreamWithKey tswk)) {
                            continue;
                        }
                        System.out.println(formatSourceInput(
                            tswk,
                            previewBytesRead,
                            previewBytesWrite
                        ));
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof java.io.EOFException) break;
                    throw e;
                }
            }
        } else {
            boolean emitRaw = "dump-both".equals(mode);
            var prefix = emitRaw ? "msg " : "";
            var dumper = new HttpTransactionDumper(System.out, prefix);
            var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(observedPacketConnectionTimeout),
                "(see command line option " + packetTimeoutParamName + ")",
                dumper
            );
            try {
                while (true) {
                    try {
                        var chunks = source.readNextTrafficStreamChunk(topContext::createReadChunkContext).get();
                        for (var sourceInput : chunks) {
                            if (emitRaw
                                && sourceInput instanceof ITrafficStreamWithKey tswk) {
                                System.out.println(
                                    "RAW " + formatSourceInput(tswk, previewBytesRead, previewBytesWrite)
                                );
                            }
                            accumulator.accept(sourceInput);
                        }
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (e.getCause() instanceof java.io.EOFException) break;
                        throw e;
                    }
                }
            } finally {
                accumulator.close();
            }
        }
    }

*/
// REBUILD-LIMBO-END(G3)
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
     * <p>Replay intake is driven inline rather than on its own thread. One thread applying batches in order is
     * the same correctness model, and it removes the need for a barrier: the design's barrier is intake
     * submitting {@code RequestNextPartitionBatch} once it has fully applied a batch ({@code kafkaLLD §4.2}),
     * whose submission is {@code §13}'s and therefore G7's.
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
    ) {
        var dumper = new HttpTransactionDumper(System.out, "msg ");
        // No telemetry is configured in a dump, and none is needed: the queue's wakeup controller exists to
        // interrupt a poll the Kafka source owner is sitting in, and there is no such owner here.
        var sourceInputs = new org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue(
            new org.opensearch.migrations.replay.kafkasource.WakeupController(
                () -> {},
                new org.opensearch.migrations.replay.tracing.KafkaSourceRootContext(
                    io.opentelemetry.api.OpenTelemetry.noop())
            )
        );
        var intake = new org.opensearch.migrations.replay.intake.ReplayIntakeOwner(
            new org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue(),
            sourceInputs,
            dumper,
            failure -> {
                throw failure;
            }
        );

        // One local generation per assigned partition, allocated once: a dump never rebalances, so a partition
        // is read by exactly one generation from start to end.
        var generations = new LinkedHashMap<TopicPartition, PartitionGenerationId>();
        for (var partition : partitions) {
            var generation = new PartitionGenerationId(partition, 0);
            generations.put(partition, generation);
            intake.applyOnCallingThread(
                new org.opensearch.migrations.replay.intake.ReplayIntakeInput.PartitionGenerationAssigned(
                    generation)
            );
        }

        var batchSequence = 0L;
        var finishedPartitions = new HashSet<TopicPartition>();
        while (finishedPartitions.size() < endOffsets.size() && !isAtEnd(consumer, endOffsets)) {
            var polled = consumer.poll(Duration.ofSeconds(2));
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
                intake.applyOnCallingThread(
                    new org.opensearch.migrations.replay.intake.ReplayIntakeInput.PartitionRecordBatch(
                        new PartitionBatchRequestId(generations.get(entry.getKey()), batchSequence),
                        entry.getValue()
                    )
                );
            }
            reportViolations(sourceInputs);
        }
        reportViolations(sourceInputs);
    }

    /**
     * Prints anything intake reported as invalid capture input and discards the rest.
     *
     * <p>Record completions are discarded deliberately: a dump holds no commit authority, so completion is the
     * one message it has nothing to do with. Draining rather than ignoring matters because the queue would
     * otherwise grow by one entry per record for the length of the topic.
     */
    private static void reportViolations(
        org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue sourceInputs
    ) {
        for (var input : sourceInputs.drain()) {
            if (input instanceof org.opensearch.migrations.replay.kafkasource.KafkaSourceInput
                    .CaptureProtocolViolationDetected violation) {
                System.out.println("msg VIOLATION " + violation.recordId() + " " + violation.diagnostic());
            }
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

// REBUILD-LIMBO-START(G3)
// runHttpFromKafka -- the Kafka dump-http/dump-both driver. Blocked on
// CapturedTrafficToHttpTransactionAccumulator, ChannelContextManager and RootReplayerContext.
// To restore: un-mark this and processHttpRecords, add a RootReplayerContext parameter to
// runDumpFromKafka, and replace the throw in its else branch with
//     boolean emitRaw = "dump-both".equals(mode);
//     runHttpFromKafka(consumer, endOffsets, endOffset, endTime, previewBytesRead, previewBytesWrite,
//         emitRaw, observedPacketConnectionTimeout, packetTimeoutParamName, topContext);
// The context is constructed by the marked region in TrafficReplayer.runDumpMode.
/*
    @SuppressWarnings("java:S1854")
    private void runHttpFromKafka(
        KafkaConsumer<String, byte[]> consumer,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite,
        boolean emitRaw,
        int observedPacketConnectionTimeout, String packetTimeoutParamName,
        RootReplayerContext topContext
    ) {
        var channelContextManager = new ChannelContextManager(topContext);
        var dumper = new HttpTransactionDumper(System.out, "msg ");
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(observedPacketConnectionTimeout),
            "(see command line option " + packetTimeoutParamName + ")",
            dumper
        );
        try {
            while (!isAtEnd(consumer, endOffsets)) {
                var polled = consumer.poll(Duration.ofSeconds(2));
                if (processHttpRecords(polled, endOffset, endTime, endOffsets,
                    emitRaw, previewBytesRead, previewBytesWrite,
                    accumulator, dumper, channelContextManager, topContext)) {
                    return;
                }
            }
        } finally {
            accumulator.close();
        }
    }

*/
// REBUILD-LIMBO-END(G3)
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

// REBUILD-LIMBO-START(G3)
// processHttpRecords -- applies each record to the accumulator, and carries the exhaustive
// CaptureRecord.payload switch for the HTTP path. Blocked on the accumulator, PojoTrafficStreamAndKey,
// TrafficStreamKeyWithKafkaRecordId, PojoKafkaCommitOffsetData and RootReplayerContext. Note the switch
// already matches kafkaLLD 7.1 exactly, so G3 refactors its identities rather than its shape.
/*
    private boolean processHttpRecords(
        ConsumerRecords<String, byte[]> records,
        Long endOffset, Long endTime,
        Map<TopicPartition, Long> endOffsets,
        boolean emitRaw, int previewBytesRead, int previewBytesWrite,
        CapturedTrafficToHttpTransactionAccumulator accumulator,
        HttpTransactionDumper dumper,
        ChannelContextManager channelContextManager,
        RootReplayerContext topContext
    ) {
        for (var rec : records) {
            if (pastEnd(rec, endOffset, endTime, endOffsets)) return true;
            try {
                var captureRecord = CaptureRecord.parseFrom(rec.value());
                switch (captureRecord.getPayloadCase()) {
                    case TRAFFICSTREAM -> {
                        var trafficStream = captureRecord.getTrafficStream();
                        getBaseEpoch(trafficStream);
                        dumper.setBaseEpochSeconds(baseEpoch);
                        if (emitRaw) {
                            System.out.println("RAW " + TrafficStreamDumper.format(
                                captureRecord,
                                rec.partition(),
                                rec.offset(),
                                previewBytesRead,
                                previewBytesWrite,
                                baseEpoch
                            ));
                        }
                        accumulator.accept(new PojoTrafficStreamAndKey(
                            trafficStream,
                            new TrafficStreamKeyWithKafkaRecordId(
                                tsk -> {
                                    var channelCtx = channelContextManager.retainOrCreateContext(tsk);
                                    return topContext.createTrafficStreamContextForKafkaSource(
                                        channelCtx,
                                        rec.key(),
                                        0
                                    );
                                },
                                trafficStream,
                                new PojoKafkaCommitOffsetData(0, rec.partition(), rec.offset())
                            )
                        ));
                    }
                    case WRITERPARTITIONHEARTBEAT, CAPTURECAPABILITYPROBE -> {
                        if (emitRaw) {
                            System.out.println("RAW " + TrafficStreamDumper.format(
                                captureRecord,
                                rec.partition(),
                                rec.offset(),
                                previewBytesRead,
                                previewBytesWrite,
                                baseEpoch
                            ));
                        }
                    }
                    case PAYLOAD_NOT_SET -> throw new CaptureRecordProtocolViolationException(
                        "CaptureRecord.payload is not set at "
                            + rec.topic()
                            + "-"
                            + rec.partition()
                            + "@"
                            + rec.offset()
                    );
                }
            } catch (InvalidProtocolBufferException e) {
                throw protocolViolation(rec, e);
            }
        }
        return false;
    }

    private String formatSourceInput(
        ITrafficStreamWithKey sourceInput,
        int previewBytesRead,
        int previewBytesWrite
    ) {
        if (sourceInput instanceof KafkaCaptureControlRecord controlRecord) {
            return TrafficStreamDumper.format(
                controlRecord.getCaptureRecord(),
                -1,
                -1,
                previewBytesRead,
                previewBytesWrite,
                baseEpoch
            );
        }
        var trafficStream = sourceInput.getStream();
        return TrafficStreamDumper.format(
            trafficStream,
            -1,
            -1,
            previewBytesRead,
            previewBytesWrite,
            getBaseEpoch(trafficStream)
        );
    }

*/
// REBUILD-LIMBO-END(G3)
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
