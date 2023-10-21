package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.time.Duration;
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

@Slf4j
public class ExhaustiveCapturedTrafficToHttpTransactionAccumulator {

    public static final int MAX_COMMANDS_IN_CONNECTION = 256;
    public static final int MAX_BUFFER_SIZE = 4096;
    public static final int MAX_BUFFER_SIZE_MULTIPLIER = 4;
    public static final int MAX_READS_IN_REQUEST = 10;
    public static final int MAX_WRITES_IN_RESPONSE = 10;
    private static final double FLUSH_LIKELIHOOD = 0.1;

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

    private static void fillCommandsAndSizes(Random r, List<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> commands, List<Integer> sizes) {
        var numTransactions = r.nextInt(MAX_COMMANDS_IN_CONNECTION);
        for (int i=numTransactions; i>0; --i) {
            addCommands(r, r.nextInt(MAX_READS_IN_REQUEST), commands, sizes,
                    ()-> SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.read(r.nextInt(MAX_BUFFER_SIZE_MULTIPLIER * MAX_BUFFER_SIZE)));
            commands.add(SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.eom());
            addCommands(r, r.nextInt(MAX_WRITES_IN_RESPONSE), commands, sizes,
                    ()-> SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.write(r.nextInt(MAX_BUFFER_SIZE_MULTIPLIER * MAX_BUFFER_SIZE)));
        }
    }

    private static void addCommands(Random r, int numPacketCommands,
                                    List<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> outgoingCommands, List<Integer> outgoingSizes,
                                    Supplier<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> directiveSupplier) {
        var aggregateBufferSize = new AtomicInteger();
        for (var cmdCount=new AtomicInteger(numPacketCommands); cmdCount.get()>0;) {
            outgoingCommands.add(getFlushOrSupply(r, cmdCount, directiveSupplier));
        }
        outgoingSizes.add(aggregateBufferSize.get());
    }

    private static SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective getFlushOrSupply(Random r, AtomicInteger i,
                                                                                                               Supplier<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> supplier) {
        return supplyRandomly(r, FLUSH_LIKELIHOOD, () -> SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.flush(),
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

    private static TrafficStream[] fillCommandsAndSizesForSeed(long rSeed,
                                                               ArrayList<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> commands,
                                                               ArrayList<Integer> sizes) throws Exception {
        var r2 = new Random(rSeed);
        fillCommandsAndSizes(r2, commands, sizes);
        return SimpleCapturedTrafficToHttpTransactionAccumulatorTest.makeTrafficStreams(r2.nextInt(MAX_BUFFER_SIZE), (int) rSeed, commands);
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
        var testArgs = new ArrayList<Arguments>();
        while (!possibilitiesLeftToTest.isEmpty()) {
            var rSeed = rand.nextInt();
            log.info("generating case for randomSeed="+rSeed+" combinationsLeftToTest="+possibilitiesLeftToTest.size());
            var commands = new ArrayList<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective>();
            var sizes = new ArrayList<Integer>();
            var trafficStreams = fillCommandsAndSizesForSeed(rSeed, commands, sizes);
            var numNew = classifyTrafficStream(possibilitiesLeftToTest, trafficStreams);
            if (numNew == 0) {
                continue;
            }
            log.info("Found new cases to test with seed="+rSeed);
            for (int i=0; i<trafficStreams.length; ++i) {
                testArgs.add(Arguments.of("seed=" + rSeed, i, trafficStreams, sizes));
            }
        }
        return testArgs.toArray(Arguments[]::new);
    }

    @ParameterizedTest(name="{0}.{1}")
    @MethodSource("generateAllCoveringCombinations")
    public void testAccumulatedSplit(String testName, int cutPoint,
                                     TrafficStream[] trafficStreams, List<Integer> expectedSizes) {
        accumulateWithAccumulatorPairAtPoint(trafficStreams, cutPoint, expectedSizes);
    }

    void accumulateWithAccumulatorPairAtPoint(TrafficStream[] trafficStreams, int cutPoint,
                                              List<Integer> expectedSizes) {
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        SimpleCapturedTrafficToHttpTransactionAccumulatorTest.accumulateTrafficStreamsWithNewAccumulator(Arrays.stream(trafficStreams).limit(cutPoint),
                reconstructedTransactions, requestsReceived);
        SimpleCapturedTrafficToHttpTransactionAccumulatorTest.accumulateTrafficStreamsWithNewAccumulator(Arrays.stream(trafficStreams).skip(cutPoint),
                reconstructedTransactions, requestsReceived);
        SimpleCapturedTrafficToHttpTransactionAccumulatorTest.assertReconstructedTransactionsMatchExpectations(reconstructedTransactions, requestsReceived, expectedSizes);
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
