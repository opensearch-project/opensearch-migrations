package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

/**
 * Encapsulates all dump-mode logic (dump-raw, dump-http, dump-both) for both
 * Kafka and file-based sources. Keeps Kafka-specific details out of TrafficReplayer.
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

    public void runDumpFromKafka(
        String mode, String brokers, String topic, String authType,
        String kafkaUserName, String kafkaPassword, String propertyFile,
        Long startOffset, Long startTime, Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite,
        int observedPacketConnectionTimeout, String packetTimeoutParamName,
        RootReplayerContext topContext
    ) throws Exception {
        var kafkaProps = KafkaTrafficCaptureSource.buildKafkaProperties(
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
                boolean emitRaw = "dump-both".equals(mode);
                runHttpFromKafka(consumer, endOffsets, endOffset, endTime,
                    previewBytesRead, previewBytesWrite, emitRaw,
                    observedPacketConnectionTimeout, packetTimeoutParamName, topContext);
            }
        }
    }

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
                    for (var tswk : chunks) {
                        System.out.println(TrafficStreamDumper.format(
                            tswk.getStream(), -1, -1, previewBytesRead, previewBytesWrite, getBaseEpoch(tswk.getStream())));
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
                        for (var tswk : chunks) {
                            if (emitRaw) {
                                System.out.println("RAW " + TrafficStreamDumper.format(
                                    tswk.getStream(), -1, -1, previewBytesRead, previewBytesWrite, getBaseEpoch(tswk.getStream())));
                            }
                            accumulator.accept(tswk);
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

    private void runRawFromKafka(
        KafkaConsumer<String, byte[]> consumer,
        Map<TopicPartition, Long> endOffsets,
        Long endOffset, Long endTime,
        int previewBytesRead, int previewBytesWrite
    ) {
        while (!isAtEnd(consumer, endOffsets)) {
            var polled = consumer.poll(Duration.ofSeconds(2));
            for (var rec : polled) {
                if (pastEnd(rec, endOffset, endTime, endOffsets)) return;
                try {
                    var ts = TrafficStream.parseFrom(rec.value());
                    System.out.println(TrafficStreamDumper.format(
                        ts, rec.partition(), rec.offset(), previewBytesRead, previewBytesWrite, getBaseEpoch(ts)));
                } catch (InvalidProtocolBufferException e) {
                    log.warn("Skipping unparseable record at p:{} o:{}", rec.partition(), rec.offset());
                }
            }
        }
    }

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
                var trafficStream = TrafficStream.parseFrom(rec.value());
                getBaseEpoch(trafficStream);
                dumper.setBaseEpochSeconds(baseEpoch);
                if (emitRaw) {
                    System.out.println("RAW " + TrafficStreamDumper.format(
                        trafficStream, rec.partition(), rec.offset(), previewBytesRead, previewBytesWrite, baseEpoch));
                }
                accumulator.accept(new PojoTrafficStreamAndKey(
                    trafficStream,
                    new TrafficStreamKeyWithKafkaRecordId(
                        tsk -> {
                            var channelCtx = channelContextManager.retainOrCreateContext(tsk);
                            return topContext.createTrafficStreamContextForKafkaSource(channelCtx, rec.key(), 0);
                        },
                        trafficStream,
                        new PojoKafkaCommitOffsetData(0, rec.partition(), rec.offset())
                    )
                ));
            } catch (InvalidProtocolBufferException e) {
                log.warn("Skipping unparseable record at p:{} o:{}", rec.partition(), rec.offset());
            }
        }
        return false;
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
