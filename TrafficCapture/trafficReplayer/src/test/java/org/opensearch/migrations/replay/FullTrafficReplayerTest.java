package org.opensearch.migrations.replay;

import com.google.common.base.Strings;
import com.google.common.collect.Streams;
import io.vavr.Tuple2;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.kafka.KafkaProtobufConsumer;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.slf4j.event.Level;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
// Turn this on to test with a live Kafka broker.  Other code changes will need to be activated too
//@Testcontainers
// It would be great to test with leak detection here, but right now this test relies upon TrafficReplayer.shutdown()
// to recycle the TrafficReplayers.  Since that shutdown process optimizes for speed of teardown, rather than tidying
// everything up as it closes the door, some leaks may be inevitable.  E.g. when work is outstanding and being sent
// to the test server, a shutdown will stop those work threads without letting them flush through all of their work
// (since that could take a very long time) and some of the work might have been followed by resource releases.
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class FullTrafficReplayerTest {

    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS*2);
    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_CONNECTION_ID = "testConnectionId";

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
    public void testSingleStreamWithCloseIsCommitted() throws Throwable {
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                TestHttpServerContext::makeResponse);
        var trafficStreamWithJustClose = TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID)
                .setConnectionId(TEST_CONNECTION_ID)
                .addSubStream(TrafficObservation.newBuilder()
                        .setClose(CloseObservation.newBuilder().build()).build())
                .build();
        var trafficSourceSupplier = new ArrayCursorTrafficSourceFactory(List.of(trafficStreamWithJustClose));
        runReplayerUntilSourceWasExhausted(0, httpServer, trafficSourceSupplier);
        Assertions.assertEquals(1, trafficSourceSupplier.nextReadCursor.get());
        log.error("done");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "3,false",
            "-1,false",
            "3,true",
//            "-1,true",
    })
    @Tag("longTest")
    public void fullTest(int testSize, boolean randomize) throws Throwable {
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                TestHttpServerContext::makeResponse);
        var streamAndConsumer = generateStreamAndTupleConsumerWithSomeChecks(testSize, randomize);
        var numExpectedRequests = streamAndConsumer._2;
        var trafficStreams = streamAndConsumer._1.collect(Collectors.toList());
        log.atInfo().setMessage(()->trafficStreams.stream().map(ts->TrafficStreamUtils.summarizeTrafficStream(ts))
                        .collect(Collectors.joining("\n"))).log();
        var trafficSourceSupplier = new ArrayCursorTrafficSourceFactory(trafficStreams);
        runReplayerUntilSourceWasExhausted(numExpectedRequests, httpServer, trafficSourceSupplier);
        Assertions.assertEquals(trafficSourceSupplier.streams.size(), trafficSourceSupplier.nextReadCursor.get());
        log.error("done");
    }

    private static void runReplayerUntilSourceWasExhausted(int numExpectedRequests,
                                                           SimpleNettyHttpServer httpServer,
                                                           Supplier<ISimpleTrafficCaptureSource> trafficSourceSupplier)
            throws Throwable {
        AtomicInteger runNumberRef = new AtomicInteger();
        var totalUniqueEverReceived = new AtomicInteger();
        var nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        var receivedPerRun = new ArrayList<Integer>();
        var totalUniqueEverReceivedSizeAfterEachRun = new ArrayList<Integer>();
        var previouslyCompletelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();

        for (; true; runNumberRef.incrementAndGet()) {
            var stopPoint = nextStopPointRef.get();
            int runNumber = runNumberRef.get();
            var counter = new AtomicInteger();
            try {
                runTrafficReplayer(trafficSourceSupplier, httpServer, (t) -> {
                    if (runNumber != runNumberRef.get()) {
                        // for an old replayer.  I'm not sure why shutdown isn't blocking until all threads are dead,
                        // but that behavior only impacts this test as far as I can tell.
                        return;
                    }
                    Assertions.assertEquals(runNumber, runNumberRef.get());
                    var key = t.uniqueRequestKey;
                    synchronized (nextStopPointRef) {
                        ISourceTrafficChannelKey tsk = key.getTrafficStreamKey();
                        var keyString = tsk.getConnectionId() + "_" + key.getSourceRequestIndex();
                        if (((TrafficStreamCursorKey)(key.getTrafficStreamKey())).arrayIndex > stopPoint) {
                            log.error("Request received after our ingest threshold. Throwing.  Discarding "+key);
                            var roughlyDoubled = stopPoint + new Random(stopPoint).nextInt(stopPoint + 1);
                            nextStopPointRef.compareAndSet(stopPoint, roughlyDoubled);
                            throw new FabricatedErrorToKillTheReplayer(false);
                        }

                        var totalUnique = null != previouslyCompletelyHandledItems.put(keyString, t) ?
                                totalUniqueEverReceived.get() :
                                totalUniqueEverReceived.incrementAndGet();

                        var c = counter.incrementAndGet();
                        log.info("counter="+c+" totalUnique="+totalUnique+" runNum="+runNumber+" key="+key);
                    }
                });
                // if this finished running without an exception, we need to stop the loop
                break;
            } catch (TrafficReplayer.TerminationException e) {
                log.atLevel(e.originalCause instanceof FabricatedErrorToKillTheReplayer ? Level.INFO : Level.ERROR)
                        .setCause(e.originalCause)
                        .setMessage(()->"broke out of the replayer, with this shutdown reason")
                        .log();
                log.atLevel(e.immediateCause == null ? Level.INFO : Level.ERROR)
                        .setCause(e.immediateCause)
                        .setMessage(()->"broke out of the replayer, with the shutdown cause=" + e.originalCause +
                                " and this immediate reason")
                        .log();
                if (!(e.originalCause instanceof FabricatedErrorToKillTheReplayer)) {
                    throw e.immediateCause;
                }
            } finally {
                waitForWorkerThreadsToStop();
                log.info("Upon appending.... counter="+counter.get()+" totalUnique="+totalUniqueEverReceived.get()+
                        " runNumber="+runNumber+" stopAt="+nextStopPointRef.get() +
                        " nextReadCursor="+((ArrayCursorTrafficSourceFactory)trafficSourceSupplier).nextReadCursor);
                log.info(Strings.repeat("\n", 20));
                receivedPerRun.add(counter.get());
                totalUniqueEverReceivedSizeAfterEachRun.add(totalUniqueEverReceived.get());
            }
        }
        var skippedPerRun = IntStream.range(0, receivedPerRun.size())
                .map(i->totalUniqueEverReceivedSizeAfterEachRun.get(i)-receivedPerRun.get(i)).toArray();
        var skippedPerRunDiffs = IntStream.range(0, receivedPerRun.size()-1)
                .map(i->(skippedPerRun[i]<=skippedPerRun[i+1]) ? 1 : 0)
                .toArray();
        var expectedSkipArray = new int[skippedPerRunDiffs.length];
        Arrays.fill(expectedSkipArray, 1);
        Assertions.assertArrayEquals(expectedSkipArray, skippedPerRunDiffs);
        Assertions.assertEquals(numExpectedRequests, totalUniqueEverReceived.get());
    }

    private static void waitForWorkerThreadsToStop() throws InterruptedException {
        var sleepMs = 2;
        final var MAX_SLEEP_MS = 100;
        while (true) {
            var rootThreadGroup = getRootThreadGroup();
            if (!foundClientPoolThread(rootThreadGroup)) {
                log.info("No client connection pool threads, done polling.");
                return;
            } else {
                log.trace("Found a client connection pool - waiting briefly and retrying.");
                Thread.sleep(sleepMs);
                sleepMs = Math.max(MAX_SLEEP_MS, sleepMs*2);
            }
        }
    }

    private static boolean foundClientPoolThread(ThreadGroup group) {
        Thread[] threads = new Thread[group.activeCount()*2];
        var numThreads = group.enumerate(threads);
        for (int i=0; i<numThreads; ++i) {
            if (threads[i].getName().startsWith(ClientConnectionPool.TARGET_CONNECTION_POOL_NAME)) {
                return true;
            }
        }

        int numGroups = group.activeGroupCount();
        ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
        numGroups = group.enumerate(groups, false);
        for (int i=0; i<numGroups; ++i) {
            if (foundClientPoolThread(groups[i])) {
                return true;
            }
        }
        return false;
    }

    private static ThreadGroup getRootThreadGroup() {
        var rootThreadGroup = Thread.currentThread().getThreadGroup();
        while (true) {
            var tmp = rootThreadGroup.getParent();
            if (tmp != null) { rootThreadGroup = tmp; }
            else { return rootThreadGroup; }
        }
    }

    private Tuple2<Stream<TrafficStream>, Integer>
    generateStreamAndTupleConsumerWithSomeChecks(int count, boolean randomize) {
        var generatedCases = count > 0 ?
                TrafficStreamGenerator.generateRandomTrafficStreamsAndSizes(IntStream.range(0,count)) :
                TrafficStreamGenerator.generateAllIndicativeRandomTrafficStreamsAndSizes();
        var testCaseArr = generatedCases.toArray(TrafficStreamGenerator.RandomTrafficStreamAndTransactionSizes[]::new);
        var aggregatedStreams = randomize ?
                randomlyInterleaveStreams(count, Arrays.stream(testCaseArr).map(c->Arrays.stream(c.trafficStreams))) :
                Arrays.stream(testCaseArr).flatMap(c->Arrays.stream(c.trafficStreams));

        var numExpectedRequests = Arrays.stream(testCaseArr).mapToInt(c->c.requestByteSizes.length).sum();
        return new Tuple2<>(aggregatedStreams, numExpectedRequests);
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

    public static <T> Stream<T> randomlyInterleaveStreams(int randomSeed, Stream<Stream<T>> orderedItemStreams) {
        List<Iterator<T>> iteratorList = orderedItemStreams
                .map(Stream::iterator)
                .filter(it->it.hasNext())
                .collect(Collectors.toCollection(()->new ArrayList<>()));
        var r = new Random(randomSeed);
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
    @ToString
    @EqualsAndHashCode
    private static class TrafficStreamCursorKey implements ITrafficStreamKey, Comparable<TrafficStreamCursorKey> {
        public final int arrayIndex;

        public final String connectionId;
        public final String nodeId;
        public final int trafficStreamIndex;


        public TrafficStreamCursorKey(TrafficStream stream, int arrayIndex) {
            connectionId = stream.getConnectionId();
            nodeId = stream.getNodeId();
            trafficStreamIndex = TrafficStreamUtils.getTrafficStreamIndex(stream);
            this.arrayIndex = arrayIndex;
        }

        @Override
        public int compareTo(TrafficStreamCursorKey other) {
            return Integer.compare(arrayIndex, other.arrayIndex);
        }
    }

    @AllArgsConstructor
    @Getter
    private static class PojoTrafficStreamWithKey implements ITrafficStreamWithKey {
        TrafficStream stream;
        ITrafficStreamKey key;
    }

    private static class ArrayCursorTrafficSourceFactory implements Supplier<ISimpleTrafficCaptureSource> {
        List<TrafficStream> streams;
        AtomicInteger nextReadCursor = new AtomicInteger();

        public ArrayCursorTrafficSourceFactory(List<TrafficStream> streams) {
            this.streams = streams;
        }

        public ISimpleTrafficCaptureSource get() {
            var rval = new ArrayCursorTrafficCaptureSource(this);
            log.error("trafficSource="+rval+" readCursor="+rval.readCursor.get()+" nextReadCursor="+ nextReadCursor.get());
            return rval;
        }
    }

    private static class ArrayCursorTrafficCaptureSource implements ISimpleTrafficCaptureSource {
        final AtomicInteger readCursor;
        final PriorityQueue<TrafficStreamCursorKey> pQueue = new PriorityQueue<>();
        ArrayCursorTrafficSourceFactory arrayCursorTrafficSourceFactory;

        public ArrayCursorTrafficCaptureSource(ArrayCursorTrafficSourceFactory arrayCursorTrafficSourceFactory) {
            this.readCursor = new AtomicInteger(arrayCursorTrafficSourceFactory.nextReadCursor.get());
            this.arrayCursorTrafficSourceFactory = arrayCursorTrafficSourceFactory;
        }

        @Override
        public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
            var idx = readCursor.getAndIncrement();
            log.info("reading chunk from index="+idx);
            if (arrayCursorTrafficSourceFactory.streams.size() <= idx) {
                return CompletableFuture.failedFuture(new EOFException());
            }
            var stream = arrayCursorTrafficSourceFactory.streams.get(idx);
            var key = new TrafficStreamCursorKey(stream, idx);
            synchronized (pQueue) {
                pQueue.add(key);
            }
            return CompletableFuture.supplyAsync(()->List.of(new PojoTrafficStreamWithKey(stream, key)));
        }

        @Override
        public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
            synchronized (pQueue) { // figure out if I need to do something more efficient later
                log.info("Commit called for "+trafficStreamKey+" with pQueue.size="+pQueue.size());
                var incomingCursor = ((TrafficStreamCursorKey)trafficStreamKey).arrayIndex;
                int topCursor = pQueue.peek().arrayIndex;
                var didRemove = pQueue.remove(trafficStreamKey);
                if (!didRemove) {
                    log.error("no item "+incomingCursor+" to remove from "+pQueue);
                }
                assert didRemove;
                if (topCursor == incomingCursor) {
                    topCursor = Optional.ofNullable(pQueue.peek()).map(k->k.getArrayIndex()).orElse(topCursor+1);
                    log.info("Commit called for "+trafficStreamKey+", and new topCursor="+topCursor);
                    arrayCursorTrafficSourceFactory.nextReadCursor.set(topCursor);
                } else {
                    log.info("Commit called for "+trafficStreamKey+", but topCursor="+topCursor);
                }
            }
        }
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
             var trafficSource = captureSourceSupplier.get();
             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.setupRunAndWaitForReplayWithShutdownChecks(Duration.ofSeconds(70), blockingTrafficSource,
                    new TimeShifter(10 * 1000), tupleReceiver);
        }
    }
}
