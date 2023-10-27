package org.opensearch.migrations.replay;

import com.google.common.collect.Streams;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.kafka.KafkaProtobufConsumer;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Testcontainers
//@WrapWithNettyLeakDetection(repetitions = 1)
public class FullTrafficReplayerTest {

    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final int TEST_RECORD_COUNT = 100;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "TEST_TRAFFIC_STREAM_ID_STRING";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS*2);


    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private KafkaContainer embeddedKafkaBroker =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));;

    @Test
    @Tag("longTest")
    public void fullTest() throws Exception {
        Random r = new Random(1);
        var kafkaConsumerProps = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false,  null);
        kafkaConsumerProps.setProperty("max.poll.interval.ms", "300000");
        var kafkaConsumer = new KafkaConsumer<String,byte[]>(kafkaConsumerProps);

        var previouslyCompletelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                TestHttpServerContext::makeResponse);
        Consumer<SourceTargetCaptureTuple> tupleReceiver = t -> {
            var key = t.sourcePair.requestKey;
            var keyString = key.trafficStreamKey + "_" + key.getSourceRequestIndex();
            previouslyCompletelyHandledItems.put(keyString, t);
        };

        var testCases = TrafficStreamGenerator.generateAllIndicativeRandomTrafficStreamsAndSizes()
                .toArray(TrafficStreamGenerator.RandomTrafficStreamAndTransactionSizes[]::new);
        loadStreamsToKafka(kafkaConsumer,
                randomlyIterleaveStreams(r, Arrays.stream(testCases).map(c-> Arrays.stream(c.trafficStreams))));
        runTrafficReplayer(kafkaConsumer, httpServer, tupleReceiver);
        //Assertions.assertEquals();
        log.error("done");
    }

    private <R extends AutoCloseable> void
    loadStreamsAsynchronouslyWithResource(KafkaConsumer<String, byte[]> kafkaConsumer, R resource, Consumer<R> loader)
            throws Exception {
        try {
            new Thread(()->loader.accept(resource)).start();
            var startTime = Instant.now();
            while (!kafkaConsumer.listTopics().isEmpty()) {
                Thread.sleep(10);
                Assertions.assertTrue(Duration.between(startTime, Instant.now()).compareTo(MAX_WAIT_TIME_FOR_TOPIC) < 0);
            }
        } finally {
            resource.close();
        }
    }

    public static <T> Stream<T> randomlyIterleaveStreams(Random r, Stream<Stream<T>> orderedItemStreams) {
        List<Iterator<T>> iteratorList = orderedItemStreams
                .map(Stream::iterator)
                .filter(it->it.hasNext())
                .collect(Collectors.toCollection(()->new ArrayList<>()));
        return Streams.stream(new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return !iteratorList.isEmpty();
            }
            @Override
            public T next() {
                var slotIdx = r.nextInt(iteratorList.size());
                var collectionIterator = iteratorList.get(slotIdx);
                var nextItem = collectionIterator.next();
                if (!collectionIterator.hasNext()) {
                    var lastIdx = iteratorList.size()-1;
                    iteratorList.set(slotIdx, iteratorList.get(lastIdx));
                    iteratorList.remove(lastIdx);
                }
                return nextItem;
            }
        });
    }

    Producer<String, byte[]> buildKafkaProducer() {
        var kafkaProps = new Properties();
        kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        // Property details: https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#delivery-timeout-ms
        kafkaProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        kafkaProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.CLIENT_ID_CONFIG, TEST_GROUP_PRODUCER_ID);
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBootstrapServers());
        try {
            return new KafkaProducer(kafkaProps);
        } catch (Exception e) {
            log.atError().setCause(e).log();
            System.exit(1);
            throw e;
        }
    }

    private void loadStreamsToKafka(KafkaConsumer<String, byte[]> kafkaConsumer,
                                    Stream<TrafficStream> streams) throws Exception {
        var kafkaProducer = buildKafkaProducer();
        var counter = new AtomicInteger();
        loadStreamsAsynchronouslyWithResource(kafkaConsumer, streams, s->s.forEach(trafficStream ->
            writeTrafficStreamRecord(kafkaProducer, new TrafficStreamWithEmbeddedKey(trafficStream),
                    "KEY_" + counter.incrementAndGet())));
    }

    private void loadStreamsToKafkaFromCompressedFile(KafkaConsumer<String, byte[]> kafkaConsumer,
                                                     String filename, int recordCount) throws Exception {
        var kafkaProducer = buildKafkaProducer();
        loadStreamsAsynchronouslyWithResource(kafkaConsumer, new V0_1TrafficCaptureSource(filename),
                originalTrafficSource -> {
                    try {
                        for (int i = 0; i < recordCount; ++i) {
                            List<ITrafficStreamWithKey> chunks = null;
                            chunks = originalTrafficSource.readNextTrafficStreamChunk().get();
                            for (int j = 0; j < chunks.size(); ++j) {
                                writeTrafficStreamRecord(kafkaProducer, chunks.get(j), "KEY_" + i + "_" + j);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @SneakyThrows
    private static void writeTrafficStreamRecord(Producer<String, byte[]> kafkaProducer,
                                                 ITrafficStreamWithKey trafficStream,
                                                 String recordId) {
        var record = new ProducerRecord(TEST_TOPIC_NAME, recordId, trafficStream.getStream().toByteArray());
        var sendFuture = kafkaProducer.send(record, (metadata, exception) -> {});
        sendFuture.get();
        Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
    }

    private static void runTrafficReplayer(KafkaConsumer<String, byte[]> kafkaConsumer,
                                           SimpleNettyHttpServer httpServer,
                                           Consumer<SourceTargetCaptureTuple> tupleReceiver) throws Exception {
        var tr = new TrafficReplayer(httpServer.localhostEndpoint(),
                new StaticAuthTransformerFactory("TEST"),
                true, 10, 10*1024,
                TrafficReplayer.buildDefaultJsonTransformer(httpServer.localhostEndpoint().getHost()));

        try (var os = new NullOutputStream();
             var bos = new BufferedOutputStream(os);
             var trafficSource = new KafkaProtobufConsumer(kafkaConsumer, TEST_TOPIC_NAME, null);
             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.runReplayWithIOStreams(Duration.ofSeconds(70), blockingTrafficSource, bos,
                    new TimeShifter(10 * 1000), tupleReceiver);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage(() -> "eating exception to check for memory leaks.").log();
            throw e;
        }
    }
}
