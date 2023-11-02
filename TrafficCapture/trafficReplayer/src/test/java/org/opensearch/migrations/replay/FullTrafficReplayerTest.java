package org.opensearch.migrations.replay;

import com.google.common.collect.Streams;
import io.vavr.Tuple2;
import lombok.AllArgsConstructor;
import lombok.Getter;
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
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.kafka.KafkaProtobufConsumer;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
//@Testcontainers
@WrapWithNettyLeakDetection(repetitions = 1)
public class FullTrafficReplayerTest {

    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final int TEST_RECORD_COUNT = 100;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "TEST_TRAFFIC_STREAM_ID_STRING";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS*2);
    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;

    @AllArgsConstructor
    private static class FabricatedErrorToKillTheReplayer extends Error {
        public final boolean doneWithTest;
    }

    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private KafkaContainer embeddedKafkaBroker;
//             = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));;

    @Test
    @Tag("longTest")
    public void fullTest() throws Exception {
        Random r = new Random(1);
        var nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                TestHttpServerContext::makeResponse);
        var streamAndConsumer = generateStreamAndTupleConsumerWithSomeChecks();
        var onNewTupleReceived = streamAndConsumer._2;
        var trafficSourceSupplier = loadStreamsToCursorArraySource(streamAndConsumer._1.collect(Collectors.toList()));

        Consumer<SourceTargetCaptureTuple> tupleReceiver = t -> {
            var stopPoint = nextStopPointRef.get();
            if (onNewTupleReceived.applyAsInt(t) > stopPoint) {
                var roughlyDoubled = stopPoint + new Random(stopPoint).nextInt(stopPoint+1);
                if (nextStopPointRef.compareAndSet(stopPoint, roughlyDoubled)) {
                    throw new FabricatedErrorToKillTheReplayer(false);
                } else {
                    // somebody else got to this, don't worry about it
                }
            }
        };

        for (AtomicInteger runNumberRef = new AtomicInteger(); true; runNumberRef.incrementAndGet()) {
            int runNumber = runNumberRef.get();
            try {
                runTrafficReplayer(trafficSourceSupplier, httpServer, (t) -> {
                    Assertions.assertEquals(runNumber, runNumberRef.get());
                    tupleReceiver.accept(t);
                });
            } catch (FabricatedErrorToKillTheReplayer e) {
                if (e.doneWithTest) {
                    break;
                } else {
                    log.error("broke out of the replayer, but the doneWithTest flag was false");
                }
            }
        }

        //Assertions.assertEquals();
        log.error("done");
    }

    private Tuple2<Stream<TrafficStream>, ToIntFunction<SourceTargetCaptureTuple>>
    generateStreamAndTupleConsumerWithSomeChecks() {
        return generateStreamAndTupleConsumerWithSomeChecks(-1);
    }

    private Tuple2<Stream<TrafficStream>, ToIntFunction<SourceTargetCaptureTuple>>
    generateStreamAndTupleConsumerWithSomeChecks(int count) {
        Random r = new Random(1);
        var generatedCases = count > 0 ?
                TrafficStreamGenerator.generateRandomTrafficStreamsAndSizes(IntStream.range(0,count)) :
                TrafficStreamGenerator.generateAllIndicativeRandomTrafficStreamsAndSizes();
        var testCaseArr = generatedCases.toArray(TrafficStreamGenerator.RandomTrafficStreamAndTransactionSizes[]::new);
        var shuffledStreams =
                randomlyInterleaveStreams(r, Arrays.stream(testCaseArr).map(c->Arrays.stream(c.trafficStreams)));
        var numExpectedRequests = Arrays.stream(testCaseArr).mapToInt(c->c.requestByteSizes.length).sum();

        var previouslyCompletelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();
        return new Tuple2<>(shuffledStreams, t -> {
            var key = t.uniqueRequestKey;
            var keyString = key.getTrafficStreamKey() + "_" + key.getSourceRequestIndex();
            previouslyCompletelyHandledItems.put(keyString, t);
            var newSize = previouslyCompletelyHandledItems.size();
            if (newSize >= numExpectedRequests) {
                throw new FabricatedErrorToKillTheReplayer(true);
            }
            return newSize;
        });
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

    public static <T> Stream<T> randomlyInterleaveStreams(Random r, Stream<Stream<T>> orderedItemStreams) {
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

    @Getter
    private static class TrafficStreamCursorKey implements ITrafficStreamKey, Comparable<TrafficStreamCursorKey> {
        public final String connectionId;
        public final String nodeId;
        public final int trafficStreamIndex;

        public final int sourceListIndex;

        public TrafficStreamCursorKey(TrafficStream stream, int sourceListIndex) {
            connectionId = stream.getConnectionId();
            nodeId = stream.getNodeId();
            trafficStreamIndex = TrafficStreamUtils.getTrafficStreamIndex(stream);
            this.sourceListIndex = this.getSourceListIndex();
        }

        @Override
        public int compareTo(TrafficStreamCursorKey other) {
            return Integer.compare(sourceListIndex, other.sourceListIndex);
        }
    }

    @AllArgsConstructor
    @Getter
    private static class PojoTrafficStreamWithKey implements ITrafficStreamWithKey {
        TrafficStream stream;
        ITrafficStreamKey key;
    }

    private Supplier<ISimpleTrafficCaptureSource> loadStreamsToCursorArraySource(List<TrafficStream> streams) {
        var commitCursor = new AtomicInteger(-1);

        return () -> new ISimpleTrafficCaptureSource() {
            AtomicInteger readCursor = new AtomicInteger(commitCursor.get()+1);
            PriorityQueue<TrafficStreamCursorKey> pQueue = new PriorityQueue<>();

            @Override
            public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
                var idx = readCursor.getAndIncrement();
                if (streams.size() <= idx) {
                    return CompletableFuture.failedFuture(new EOFException());
                }
                var stream = streams.get(idx);
                var key = new TrafficStreamCursorKey(stream, idx);
                pQueue.add(key);
                return CompletableFuture.supplyAsync(()->List.of(new PojoTrafficStreamWithKey(stream, key)));
            }

            @Override
            public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
                synchronized (readCursor) { // figure out if I need to do something faster later
                    int topCursor = pQueue.peek().sourceListIndex;
                    var didRemove = pQueue.remove(trafficStreamKey);
                    assert didRemove;
                    var incomingCursor = trafficStreamKey.getTrafficStreamIndex();
                    if (topCursor == incomingCursor) {
                        topCursor = Optional.ofNullable(pQueue.peek()).map(k->k.getSourceListIndex()).orElse(topCursor);
                        commitCursor.set(topCursor);
                    }
                }
            }
        };
    }

    private Supplier<ISimpleTrafficCaptureSource> loadStreamsToKafka(Stream<TrafficStream> streams) throws Exception {
        var kafkaConsumerProps = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false,  null);
        kafkaConsumerProps.setProperty("max.poll.interval.ms", "300000");
        var kafkaConsumer = new KafkaConsumer<String,byte[]>(kafkaConsumerProps);

        var kafkaProducer = buildKafkaProducer();
        var counter = new AtomicInteger();
        loadStreamsAsynchronouslyWithResource(kafkaConsumer, streams, s->s.forEach(trafficStream ->
            writeTrafficStreamRecord(kafkaProducer, new TrafficStreamWithEmbeddedKey(trafficStream),
                    "KEY_" + counter.incrementAndGet())));

        return () -> new KafkaProtobufConsumer(kafkaConsumer, TEST_TOPIC_NAME, null);
    }

    private Supplier<ISimpleTrafficCaptureSource>
    loadStreamsToKafkaFromCompressedFile(KafkaConsumer<String, byte[]> kafkaConsumer,
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
        return () -> new KafkaProtobufConsumer(kafkaConsumer, TEST_TOPIC_NAME, null);
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

    private static void runTrafficReplayer(Supplier<ISimpleTrafficCaptureSource> captureSourceSupplier,
                                           SimpleNettyHttpServer httpServer,
                                           Consumer<SourceTargetCaptureTuple> tupleReceiver) throws Exception {
        log.info("Starting a new replayer and running it");
        var tr = new TrafficReplayer(httpServer.localhostEndpoint(),
                new StaticAuthTransformerFactory("TEST"),
                true, 10, 10*1024,
                TrafficReplayer.buildDefaultJsonTransformer(httpServer.localhostEndpoint().getHost()));

        try (var os = new NullOutputStream();
             var bos = new BufferedOutputStream(os);
             var trafficSource = captureSourceSupplier.get();
             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.runReplayWithIOStreams(Duration.ofSeconds(70), blockingTrafficSource, bos,
                    new TimeShifter(10 * 1000), tupleReceiver);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage(() -> "eating exception to check for memory leaks.").log();
            throw new RuntimeException(e);
        }
    }
}
