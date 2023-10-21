package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.replay.datatypes.RawPackets;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Some things to consider - Reads, Writes, ReadSegments, WriteSegments, EndOfSegment, EndOfMessage
 * In a well-formed stream, these will always occur in the pattern
 *   `(Read | (ReadSegment* EndOfSegment))* EndOfMessage (Write | (WriteSegment* EndOfSegment))*`
 *
 * The pattern may be repeated any number of times and that pattern may be truncated at any point.
 * However, once a stream of observations is truncated, it is a permanent truncation.  No additional
 * observations will be present, making truncations but not excisions possible.
 *
 * That means that there are 7 types of messages (counting EndOfSegment twice since the context will
 * make it imply EndOfReadSegments or EndOfWriteSegments).  Therefore, there are 7 different types of
 * observations that could be at the beginning of a TrafficStream and 7 different types that could be
 * the last observation of a TrafficStream.  That's already 49 possibilities
 * @return
 */
@Slf4j
public class CapturedTrafficToHttpTransactionAccumulatorTest {

    public static final int MAX_COMMANDS_IN_CONNECTION = 256;
    public static final int MAX_BUFFER_SIZE = 4096;
    public static final int MAX_BUFFER_SIZE_MULTIPLIER = 4;
    public static final int MAX_READS_IN_REQUEST = 10;
    public static final int MAX_WRITES_IN_RESPONSE = 10;
    private static final double FLUSH_LIKELIHOOD = 0.1;

    private enum OffloaderCommandType {
        Read,
        EndOfMessage,
        Write,
        Flush
    }

    @AllArgsConstructor
    private static class ObservationDirective {
        private final OffloaderCommandType offloaderCommandType;
        private final int size;

        public static ObservationDirective read(int i) {
            return new ObservationDirective(OffloaderCommandType.Read, i);
        }
        public static ObservationDirective eom() {
            return new ObservationDirective(OffloaderCommandType.EndOfMessage, 0);
        }
        public static ObservationDirective write(int i) {
            return new ObservationDirective(OffloaderCommandType.Write, i);
        }
        public static ObservationDirective flush() {
            return new ObservationDirective(OffloaderCommandType.Flush, 0);
        }
    }

    public static InMemoryConnectionCaptureFactory buildSerializerFactory(int bufferSize, Runnable onClosedCallback) {
        return new InMemoryConnectionCaptureFactory("TEST_NODE_ID", bufferSize, onClosedCallback);
    }

    static ByteBuf makeSequentialByteBuf(int offset, int size) {
        var bb = Unpooled.buffer(size);
        for (int i=0; i<size; ++i) {
            //bb.writeByte((i+offset)%255);
            bb.writeByte('A'+offset);
        }
        return bb;
    }

    static TrafficStream[] makeTrafficStreams(int bufferSize, int interactionOffset,
                                             List<ObservationDirective> directives)
            throws Exception {
        var connectionFactory = buildSerializerFactory(bufferSize, ()->{});
        var offloader = connectionFactory.createOffloader("TEST_CONNECTION_ID");
        for (var directive : directives) {
            offloadHttpTransaction(offloader, interactionOffset++, directive);
        }
        offloader.addCloseEvent(Instant.EPOCH);
        offloader.flushCommitAndResetStream(true).get();
        return connectionFactory.getRecordedTrafficStreamsStream().toArray(TrafficStream[]::new);
    }

    private static void offloadHttpTransaction(IChannelConnectionCaptureSerializer offloader, int offset,
                                        ObservationDirective directive) throws IOException {
        switch (directive.offloaderCommandType) {
            case Read:
                offloader.addReadEvent(Instant.EPOCH, makeSequentialByteBuf(offset, directive.size));
                return;
            case EndOfMessage:
                offloader.commitEndOfHttpMessageIndicator(Instant.EPOCH);
                return;
            case Write:
                offloader.addWriteEvent(Instant.EPOCH, makeSequentialByteBuf(offset+3, directive.size));
                return;
            case Flush:
                offloader.flushCommitAndResetStream(false);
                return;
            default:
                throw new RuntimeException("Unknown directive type: " + directive.offloaderCommandType);
        }
    }

    long aggregateSizeOfPacketBytes(RawPackets packetBytes) {
        return packetBytes.stream().mapToInt(bArr->bArr.length).sum();
    }

    public static Arguments[] loadCombinations() {
        return new Arguments[] {
                Arguments.of("easyTransactionsAreHandledCorrectly",
                        1024*1024, 0,
                        List.of(ObservationDirective.read(1024),
                                ObservationDirective.eom(),
                                ObservationDirective.write(1024)),
                        List.of(1024, 1024)),
                Arguments.of("transactionStartingWithSegmentsAreHandledCorrectly",
                        1024, 0,
                        List.of(ObservationDirective.read(1024),
                                ObservationDirective.eom(),
                                ObservationDirective.write(1024)),
                        List.of(1024, 1024)),
                Arguments.of("skippedTrafficStreamWithNextStartingOnSegmentsAreHandledCorrectly",
                        512, 1,
                        List.of(ObservationDirective.read(MAX_COMMANDS_IN_CONNECTION),
                                ObservationDirective.eom(),
                                ObservationDirective.write(MAX_COMMANDS_IN_CONNECTION),
                                ObservationDirective.read(MAX_COMMANDS_IN_CONNECTION),
                                ObservationDirective.eom(),
                                ObservationDirective.write(MAX_COMMANDS_IN_CONNECTION)),
                        List.of(MAX_COMMANDS_IN_CONNECTION, MAX_COMMANDS_IN_CONNECTION))
        };
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("loadCombinations")
    void generateAndTest(String testName, int bufferSize, int skipCount,
                         List<ObservationDirective> directives, List<Integer> expectedSizes) throws Exception {
        var trafficStreams = Arrays.stream(makeTrafficStreams(bufferSize, 0, directives)).skip(skipCount);
        testWithExpectedSizes(testName, trafficStreams, expectedSizes);
    }

    void testWithExpectedSizes(String testName, Stream<TrafficStream> trafficStreams, List<Integer> expectedSizes) {
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        sendTrafficStreamsToNewAccumulator(trafficStreams, reconstructedTransactions, requestsReceived);
        assertReconstructedTransactionsMatchExpectations(reconstructedTransactions, requestsReceived, expectedSizes);
    }

    private void assertReconstructedTransactionsMatchExpectations(List<RequestResponsePacketPair> reconstructedTransactions,
                                                                  AtomicInteger requestsReceived,
                                                                  List<Integer> expectedSizes) {
        log.error("reconstructedTransactions="+ reconstructedTransactions);
        var expectedCount = expectedSizes.size()/2;
        Assertions.assertEquals(expectedCount, reconstructedTransactions.size());
        for (int i = 0; i< reconstructedTransactions.size(); ++i) {
            Assertions.assertEquals((long) expectedSizes.get(i*2),
                    aggregateSizeOfPacketBytes(reconstructedTransactions.get(i).requestData.packetBytes));
            Assertions.assertEquals((long) expectedSizes.get(i*2+1),
                    aggregateSizeOfPacketBytes(reconstructedTransactions.get(i).responseData.packetBytes));
        }
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
    }

    private static void sendTrafficStreamsToNewAccumulator(Stream<TrafficStream> trafficStreams, List<RequestResponsePacketPair> reconstructedTransactions, AtomicInteger requestsReceived) {
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30), null,
                        (id,request) -> requestsReceived.incrementAndGet(),
                        fullPair -> reconstructedTransactions.add(fullPair),
                        (rk,ts) -> {}
                );
        var tsList = trafficStreams.collect(Collectors.toList());
        trafficStreams = tsList.stream();
        trafficStreams.forEach(ts->trafficAccumulator.accept(new TrafficStreamWithEmbeddedKey(ts)));
        trafficAccumulator.close();
    }

    enum ObservationType {
        Read(0),
        ReadSegment(1),
        EndOfReadSegment(2),
        EOM(3),
        Write(MAX_BUFFER_SIZE_MULTIPLIER),
        WriteSegment(5),
        EndOfWriteSegment(6);

        private final int intValue;

        private ObservationType(int intValue) {
            this.intValue = intValue;
        }

        public int getIntValue() {
            return intValue;
        }

        public static Stream<ObservationType> valueStream() {
            return Arrays.stream(ObservationType.values());
        }
    }

    private static int makeClassificationValue(ObservationType ot1, ObservationType ot2, Integer count) {
        return (((ot1.intValue * 255) + ot2.intValue) * 255) + count;
    }

    public static Arguments[] generateAllCoveringCombinations() throws Exception {
        // track all possibilities of start + end + observation count - all per a TrafficStream.
        // notice that one created sequence of streams may have a number of different classifications of streams
        var possibilitiesLeftToTest = new HashSet<Integer>();
        ObservationType.valueStream()
                        .flatMap(ot1->ObservationType.valueStream().flatMap(ot2->
                                Stream.of(1,2,3).map(count->makeClassificationValue(ot1, ot2, count))))
                .forEach(i->possibilitiesLeftToTest.add(i));

        var rand = new Random(2);
        int counter = 0;
        var testArgs = new ArrayList<Arguments>();
        while (!possibilitiesLeftToTest.isEmpty()) {
            var rSeed = rand.nextInt();
            var r2 = new Random(rSeed);
            log.info("generating case for randomSeed="+rSeed+" combinationsLeftToTest="+possibilitiesLeftToTest.size());
            var commands = new ArrayList<ObservationDirective>();
            var sizes = new ArrayList<Integer>();
            fillCommandsAndSizes(r2, commands, sizes);
            var trafficStreams = makeTrafficStreams(r2.nextInt(MAX_BUFFER_SIZE), counter++, commands);
            var numNew = classifyTrafficStream(possibilitiesLeftToTest, trafficStreams);
            if (numNew == 0) {
                continue;
            }
            log.info("Found new cases to test with seed="+rSeed);
            testArgs.add(Arguments.of("seed="+rSeed, trafficStreams, sizes));
        }
        return testArgs.toArray(Arguments[]::new);
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("generateAllCoveringCombinations")
    private void testAllAccumulatedSplits(String testName, TrafficStream[] trafficStreams, List<Integer> expectedSizes) {
        for (int cutPoint=0; cutPoint<trafficStreams.length; ++cutPoint) {
            accumulateWithAccumulatorPairAtPoint(trafficStreams, cutPoint, expectedSizes);
        }
    }

    void accumulateWithAccumulatorPairAtPoint(TrafficStream[] trafficStreams, int cutPoint, List<Integer> expectedSizes) {
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        sendTrafficStreamsToNewAccumulator(Arrays.stream(trafficStreams).limit(cutPoint),
                reconstructedTransactions, requestsReceived);
        sendTrafficStreamsToNewAccumulator(Arrays.stream(trafficStreams).skip(cutPoint),
                reconstructedTransactions, requestsReceived);
        assertReconstructedTransactionsMatchExpectations(reconstructedTransactions, requestsReceived, expectedSizes);
    }

    private static void fillCommandsAndSizes(Random r, List<ObservationDirective> commands, List<Integer> sizes) {
        var numTransactions = r.nextInt(MAX_COMMANDS_IN_CONNECTION);
        for (int i=numTransactions; i>0; --i) {
            addCommands(r, r.nextInt(MAX_READS_IN_REQUEST), commands, sizes,
                    ()->ObservationDirective.read(r.nextInt(MAX_BUFFER_SIZE_MULTIPLIER * MAX_BUFFER_SIZE)));
            commands.add(ObservationDirective.eom());
            addCommands(r, r.nextInt(MAX_WRITES_IN_RESPONSE), commands, sizes,
                    ()->ObservationDirective.write(r.nextInt(MAX_BUFFER_SIZE_MULTIPLIER * MAX_BUFFER_SIZE)));
        }
    }

    private static void addCommands(Random r, int numPacketCommands, List<ObservationDirective> commands, List<Integer> sizes,
                             Supplier<ObservationDirective> directiveSupplier) {
        var aggregateBufferSize = new AtomicInteger();
        for (var cmdCount=new AtomicInteger(numPacketCommands); cmdCount.get()>0;) {
            commands.add(getFlushOrSupply(r, cmdCount, directiveSupplier));
        }
        sizes.add(aggregateBufferSize.get());
    }

    private static ObservationDirective getFlushOrSupply(Random r, AtomicInteger i, Supplier<ObservationDirective> supplier) {
        return supplyRandomly(r, FLUSH_LIKELIHOOD, () -> ObservationDirective.flush(),
                () -> {
                    i.decrementAndGet();
                    return supplier.get();
                });
    }

    private static <T> T supplyRandomly(Random r, double p1, Supplier<T> supplier1, Supplier<T> supplier2) {
        return (r.nextDouble() <= p1) ? supplier1.get() : supplier2.get();
    }

    private static int classifyTrafficStream(HashSet<Integer> possibilitiesLeftToTest, TrafficStream[] trafficStreams) {
        int counter = 0;
        ObservationType previousObservationType = ObservationType.Read;
        for (var ts : trafficStreams) {
            var ssl = ts.getSubStreamList();
            ObservationType outgoingLastType = previousObservationType;
            for (int i=ssl.size()-1; i>=0; --i) {
                var outgoingLastTypeOp = getTypeFromObservation(ssl.get(i));
                if (!outgoingLastTypeOp.isEmpty()) {
                    outgoingLastType = outgoingLastTypeOp.get();
                    break;
                }
            }
            var type = makeClassificationValue(getTypeFromObservation(ssl.get(0), previousObservationType),
                    outgoingLastType, ssl.size());
            previousObservationType = outgoingLastType;
            counter += possibilitiesLeftToTest.remove(type) ? 1 : 0;
        }
        return counter;
    }

    private static Optional<ObservationType> getTypeFromObservation(TrafficObservation trafficObservation) {
        if (trafficObservation.hasRead()) {
            return Optional.of(ObservationType.Read);
        } else if (trafficObservation.hasReadSegment()) {
            return Optional.of(ObservationType.ReadSegment);
        } else if (trafficObservation.hasWrite()) {
            return Optional.of(ObservationType.Write);
        } else if (trafficObservation.hasWriteSegment()) {
            return Optional.of(ObservationType.WriteSegment);
        } else if (trafficObservation.hasEndOfMessageIndicator()) {
            return Optional.of(ObservationType.EOM);
        } else if (trafficObservation.hasSegmentEnd()) {
            return Optional.empty();
        } else {
            throw new RuntimeException("unknown traffic observation: " + trafficObservation);
        }
    }


    private static ObservationType getTypeFromObservation(TrafficObservation trafficObservation,
                                                          ObservationType lastObservationType) {
        return getTypeFromObservation(trafficObservation).orElseGet(() -> {
            assert trafficObservation.hasSegmentEnd();
            switch (lastObservationType) {
                case Read:
                case ReadSegment:
                    return ObservationType.EndOfReadSegment;
                case EOM:
                case Write:
                case WriteSegment:
                    return ObservationType.EndOfWriteSegment;
                default:
                    throw new RuntimeException("previous observation type doesn't match expected possibilities: " +
                            lastObservationType);
            }
        });
    }
}
