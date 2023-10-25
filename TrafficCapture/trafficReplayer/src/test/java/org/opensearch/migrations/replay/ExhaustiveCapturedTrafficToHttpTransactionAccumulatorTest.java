package org.opensearch.migrations.replay;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
public class ExhaustiveCapturedTrafficToHttpTransactionAccumulatorTest {

    private static final int CLASSIFY_COMPONENT_INT_SHIFT = 255;

    public static final int MAX_COMMANDS_IN_CONNECTION = 8;
    public static final int MIN_BUFFER_SIZE = 100;
    public static final int MAX_BUFFER_SIZE = 2048;
    public static final double MAX_BUFFER_SIZE_MULTIPLIER = 2.0;
    public static final int MAX_READS_IN_REQUEST = 5;
    public static final int MAX_WRITES_IN_RESPONSE = 5;

    public enum ObservationType {
        Read(0),
        ReadSegment(1),
        EndOfReadSegment(2),
        EOM(3),
        Write(4),
        WriteSegment(5),
        EndOfWriteSegment(6),
        Close(7);

        private final int intValue;

        private ObservationType(int intValue) {
            this.intValue = intValue;
        }

        public int getIntValue() {
            return intValue;
        }

        public static Stream<ObservationType> valueStream() {
            return Arrays.stream(ObservationType.values())
                    .filter(ot->ot!=ObservationType.Close);
        }
    }

    /**
     * This converts summary statistics for a TrafficStream into a single scalar that identifies the
     * traits that we're interested in tracking for test purposes.
     * Traits that are interesting: Type of observation for the first and last substream's observations.
     * It would be nice to also track how many observations are within a given TrafficStream but that
     * will require mapping which transitions are possible after how many steps.  It will also require
     * a bit more work to fully implement.  For now, the count parameter is ignored.
     */
    private static int makeClassificationValue(ObservationType ot1, ObservationType ot2, Integer count) {
        return (((0 * CLASSIFY_COMPONENT_INT_SHIFT) + ot1.intValue) * CLASSIFY_COMPONENT_INT_SHIFT) + ot2.intValue;
    }

    private static String classificationToString(int c) {
        int left = c;
        int end = c % CLASSIFY_COMPONENT_INT_SHIFT;
        left = left / CLASSIFY_COMPONENT_INT_SHIFT;
        int start = left % CLASSIFY_COMPONENT_INT_SHIFT;
        left = left / CLASSIFY_COMPONENT_INT_SHIFT;
        int size = left % CLASSIFY_COMPONENT_INT_SHIFT;
        return start + "," + end + "," + size;
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
                    getTypeFromObservation(ssl.get(ssl.size()-1), outgoingLastType), ssl.size());
            log.atTrace().setMessage(()->"classification="+classificationToString(type)).log();
            previousObservationType = outgoingLastType;
            counter += possibilitiesLeftToTest.remove(type) ? 1 : 0;
        }
        return counter;
    }

    private static void addCommands(Random r, double flushLikelihood, int numPacketCommands,
                                    List<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> outgoingCommands, List<Integer> outgoingSizes,
                                    Supplier<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> directiveSupplier) {
        int aggregateBufferSize = 0;
        for (var cmdCount=new AtomicInteger(numPacketCommands); cmdCount.get()>0;) {
            var cmd = getFlushOrSupply(r, flushLikelihood, cmdCount, directiveSupplier);
            outgoingCommands.add(cmd);
            aggregateBufferSize += cmd.size;
        }
        outgoingSizes.add(aggregateBufferSize);
    }

    private static SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective
    getFlushOrSupply(Random r, double flushLikelihood, AtomicInteger i,
                     Supplier<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> supplier) {
        return supplyRandomly(r, flushLikelihood,
                () -> SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.flush(),
                () -> {
                    i.decrementAndGet();
                    return supplier.get();
                });
    }

    private static <T> T supplyRandomly(Random r, double p1, Supplier<T> supplier1, Supplier<T> supplier2) {
        return (r.nextDouble() <= p1) ? supplier1.get() : supplier2.get();
    }

    private static void fillCommandsAndSizes(int bufferSize, Random r, double flushLikelihood, int bufferBound,
                                             List<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> commands, List<Integer> sizes) {
        var numTransactions = r.nextInt(MAX_COMMANDS_IN_CONNECTION);
        for (int i=numTransactions; i>0; --i) {
            addCommands(r, flushLikelihood, r.nextInt(MAX_READS_IN_REQUEST)+1, commands, sizes,
                    ()-> SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.read(r.nextInt(bufferBound)));
            if (r.nextDouble() <= flushLikelihood) {
                commands.add(SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.flush());
            }
            commands.add(SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.eom());
            addCommands(r, flushLikelihood, r.nextInt(MAX_WRITES_IN_RESPONSE)+1, commands, sizes,
                    ()-> SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.write(r.nextInt(bufferBound)));
            if (r.nextDouble() <= flushLikelihood) {
                commands.add(SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective.flush());
            }
        }
    }

    @SneakyThrows
    private static TrafficStream[] fillCommandsAndSizesForSeed(long rSeed,
                                                               ArrayList<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective> commands,
                                                               ArrayList<Integer> sizes) {
        var r2 = new Random(rSeed);
        var bufferSize = r2.nextInt(MAX_BUFFER_SIZE-MIN_BUFFER_SIZE) + MIN_BUFFER_SIZE;
        final var bufferBound = (int)(Math.abs(r2.nextGaussian()) * ((MAX_BUFFER_SIZE_MULTIPLIER * bufferSize)))+1;
        log.atTrace()
                .setMessage(()->String.format("bufferSize=%d bufferBound=%d maxPossibleReads=%d maxPossibleWrites=%d",
                        bufferSize, bufferBound, MAX_READS_IN_REQUEST, MAX_WRITES_IN_RESPONSE))
                .log();
        fillCommandsAndSizes(bufferSize, r2, Math.pow(r2.nextDouble(),2.0), bufferBound, commands, sizes);
        return SimpleCapturedTrafficToHttpTransactionAccumulatorTest.makeTrafficStreams(bufferSize, (int) rSeed, commands);
    }

    /**
     * track all possibilities of start + end per a TrafficStream.
     * notice that one created sequence of streams may have a number of different classifications of streams
     */
    private static HashSet<Integer> getPossibleTests() {
        // these transitions are impossible to do within one TrafficStream since segmented reads are only required
        // when the span multiple records
        var impossibleTransitions = Map.of(
                        ObservationType.Read, List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment),
                        ObservationType.ReadSegment, List.of(ObservationType.EndOfWriteSegment),
                        ObservationType.EndOfReadSegment, List.of(ObservationType.EndOfWriteSegment),
                        ObservationType.EOM, List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment),
                        ObservationType.Write, List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment),
                        ObservationType.WriteSegment, List.of(ObservationType.EndOfReadSegment),
                        ObservationType.EndOfWriteSegment, List.of(ObservationType.EndOfReadSegment))
                .entrySet().stream()
                .flatMap(kvp->kvp.getValue().stream().map(v->makeClassificationValue(kvp.getKey(), v,0)))
                .collect(Collectors.toSet());
        var possibilities = new HashSet<Integer>();
        ObservationType.valueStream()
                .flatMap(ot1->ObservationType.valueStream().map(ot2->makeClassificationValue(ot1, ot2, 0)))
                .filter(i->!impossibleTransitions.contains(i))
                .forEach(i->possibilities.add(i));
        return possibilities;
    }

    public static Arguments[] generateAllTestsAndConfirmComplete(IntStream seedStream) {
        var possibilitiesLeftToTest = getPossibleTests();
        List<Integer> seedsThatOfferUniqueTestCases = new ArrayList<>();
        var numTries = new AtomicInteger();
        var testArgs = new ArrayList<Arguments>();

        seedStream.takeWhile(i->!possibilitiesLeftToTest.isEmpty()).forEach(rSeed->{
            var commands = new ArrayList<SimpleCapturedTrafficToHttpTransactionAccumulatorTest.ObservationDirective>();
            var sizes = new ArrayList<Integer>();
            var trafficStreams = fillCommandsAndSizesForSeed(rSeed, commands, sizes);
            var numNew = classifyTrafficStream(possibilitiesLeftToTest, trafficStreams);
            if (numNew > 0) {
                var n = numTries.getAndIncrement();
                log.info("Found new cases to test with seed=" + rSeed + " tries=" + n +
                        " left=" + possibilitiesLeftToTest.size());
                seedsThatOfferUniqueTestCases.add(rSeed);

                for (int i = 0; i < trafficStreams.length; ++i)
                //int i = 0;
                {
                    testArgs.add(Arguments.of("seed=" + rSeed, i, trafficStreams, sizes));
                }
            }
        });
        //Assertions.assertTrue(possibilitiesLeftToTest.isEmpty());
        log.atInfo().setMessage(()->"Sufficient random seeds to generate a full cover of tests:{}")
                .addArgument(seedsThatOfferUniqueTestCases.stream().map(i->""+i).collect(Collectors.joining(",")))
                .log();
        return testArgs.toArray(Arguments[]::new);
    }

    static Arguments[] generateTestCombinations() throws Exception {
        var rand = new Random(1);
        return generateAllTestsAndConfirmComplete(
//                IntStream.generate(()->rand.nextInt())
                List.of(-1155869325,892128508,155629808,1429008869,-1465154083,-1242363800,26273138,
                        1705850753, -1956122223,-193570837,1558626465,1248685248,-1292756720,-3507139,929459541,
                        474550272,-957816454,-1418261474,431108934,1601212083,1788602357,1722788072,1421653156)
//                List.of(1705850753)
                        .stream().mapToInt(i->i)
        );
    }

    private static String renderLeftToTest(HashSet<Integer> possibilitiesLeftToTest) {
        return possibilitiesLeftToTest.size() + ": " +
                possibilitiesLeftToTest.stream().map(c->classificationToString(c))
                        .sorted()
                        .collect(Collectors.joining("\n"));
    }

    @ParameterizedTest(name="{0}.{1}")
    @MethodSource("generateTestCombinations")
    public void testAccumulatedSplit(String testName, int cutPoint,
                                     TrafficStream[] trafficStreams, List<Integer> expectedSizes) {
        accumulateWithAccumulatorPairAtPoint(trafficStreams, cutPoint, expectedSizes);
    }

    void accumulateWithAccumulatorPairAtPoint(TrafficStream[] trafficStreams, int cutPoint,
                                              List<Integer> expectedSizes) {
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        // some of the messages up to the cutPoint may not have been able to be fully committed (when the
        // messages rolled over past the initial cutPoint.  In those cases, we have to rewind further back
        // for the second run to make sure that we pick up those partial messages that were not received
        // in the first pass.
        //
        // Notice that this may cause duplicates.  That's by design.  The system has an at-least-once guarantee.
        var indicesProcessedPass1 =
                SimpleCapturedTrafficToHttpTransactionAccumulatorTest.accumulateTrafficStreamsWithNewAccumulator(
                        Arrays.stream(trafficStreams).limit(cutPoint), reconstructedTransactions, requestsReceived);
        cutPoint = indicesProcessedPass1.isEmpty() ? 0 : indicesProcessedPass1.last();
        var indicesProcessedPass2 =
            SimpleCapturedTrafficToHttpTransactionAccumulatorTest.accumulateTrafficStreamsWithNewAccumulator(
                    Arrays.stream(trafficStreams).skip(cutPoint), reconstructedTransactions, requestsReceived);

        // three checks to do w/ the indicesProcessed sets.
        // Count their sum, confirm that there were not duplicates, confirm all match the input indices
        Assertions.assertEquals(trafficStreams.length, indicesProcessedPass1.size()+indicesProcessedPass2.size());
        var unionSet = new TreeSet(indicesProcessedPass1);
        unionSet.addAll(indicesProcessedPass2);
        Assertions.assertEquals(trafficStreams.length, unionSet.size());
        Assertions.assertEquals(1, unionSet.first());
        Assertions.assertEquals(TrafficStreamUtils.getTrafficStreamIndex(trafficStreams[trafficStreams.length-1]),
                unionSet.last());

        SimpleCapturedTrafficToHttpTransactionAccumulatorTest.assertReconstructedTransactionsMatchExpectations(
                reconstructedTransactions, expectedSizes);
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
        } else if (trafficObservation.hasClose()) {
            return Optional.of(ObservationType.Close);
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
