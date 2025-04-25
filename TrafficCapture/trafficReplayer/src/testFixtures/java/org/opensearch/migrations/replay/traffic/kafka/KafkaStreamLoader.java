package org.opensearch.migrations.replay.traffic.kafka;

import java.io.EOFException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.kafka.KafkaTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.V0_1TrafficCaptureSource;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Lombok;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Assertions;

public class KafkaStreamLoader {
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS * 2);

    private <R extends AutoCloseable> void loadStreamsAsynchronouslyWithCloseableResource(
            KafkaConsumer<String, byte[]> kafkaConsumer,
            R closeableResource,
            Consumer<R> loader
    ) throws Exception {
        try {
            new Thread(() -> loader.accept(closeableResource)).start();
            var startTime = Instant.now();
            while (!kafkaConsumer.listTopics().isEmpty()) {
                Thread.sleep(10);
//                Assertions.assertTrue(
//                        Duration.between(startTime, Instant.now()).compareTo(MAX_WAIT_TIME_FOR_TOPIC) < 0
//                );
            }
        } finally {
            closeableResource.close();
        }
    }

    public void loadStreamsToKafka(Producer<String, byte[]> kafkaProducer,
                                   KafkaConsumer<String, byte[]> kafkaConsumer,
                                   Stream<TrafficStream> streams,
                                   String topicName)
            throws Exception {
        var counter = new AtomicInteger();
        loadStreamsAsynchronouslyWithCloseableResource(
                kafkaConsumer,
                streams,
                s -> s.forEach(
                        trafficStream -> KafkaTestUtils.writeTrafficStreamRecord(
                                kafkaProducer,
                                trafficStream,
                                topicName,//TEST_TOPIC_NAME,
                                "KEY_" + counter.incrementAndGet()
                        )
                )
        );
        Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
    }

    public Supplier<ISimpleTrafficCaptureSource> loadStreamsToKafkaFromCompressedFile(
            TestContext rootCtx,
            Producer<String, byte[]> kafkaProducer,
            KafkaConsumer<String, byte[]> kafkaConsumer,
            String topicName,
            String filename,
            long pollIntervalMs,
            int recordCount
    ) throws Exception {
        loadStreamsAsynchronouslyWithCloseableResource(
                kafkaConsumer,
                new V0_1TrafficCaptureSource(rootCtx, filename),
                originalTrafficSource -> {
                    try {
                        int i = 0;
                        while(true) {
                            List<ITrafficStreamWithKey> chunks = null;
                            chunks = originalTrafficSource.readNextTrafficStreamChunk(rootCtx::createReadChunkContext)
                                    .get();
                            if (chunks == null || chunks.isEmpty()) {
                                System.out.println("DONE!");
                                break;
                            }
                            for (int j = 0; j < chunks.size(); ++j) {
                                KafkaTestUtils.writeTrafficStreamRecord(
                                        kafkaProducer,
                                        chunks.get(j).getStream(),
                                        topicName,
                                        "KEY_" + i + "_" + j
                                );
                                //Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
                            }
                            i++;
                        }
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                }
        );
        System.out.println("Happily done!");
        return () -> new KafkaTrafficCaptureSource(
                rootCtx,
                kafkaConsumer,
                topicName,
                Duration.ofMillis(pollIntervalMs)
        );
    }
}
