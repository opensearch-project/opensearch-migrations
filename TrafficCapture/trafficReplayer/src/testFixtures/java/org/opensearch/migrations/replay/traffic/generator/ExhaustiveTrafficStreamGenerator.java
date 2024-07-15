package org.opensearch.migrations.replay.traffic.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.testutils.StreamInterleaver;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExhaustiveTrafficStreamGenerator {

    private static final int CLASSIFY_COMPONENT_INT_SHIFT = 255;

    public static final int MAX_COMMANDS_IN_CONNECTION = 8;
    public static final int MIN_BUFFER_SIZE = 100;
    public static final int MAX_BUFFER_SIZE = 2048;
    public static final double MAX_BUFFER_SIZE_MULTIPLIER = 2.0;
    public static final int MAX_READS_IN_REQUEST = 5;
    public static final int MAX_WRITES_IN_RESPONSE = 5;
    public static final List<Integer> RANDOM_GENERATOR_SEEDS_FOR_SUFFICIENT_TRAFFIC_VARIANCE = List.of(
        -1155869325,
        892128508,
        155629808,
        1429008869,
        26273138,
        685382526,
        1705850753,
        -1978864692,
        -836540342,
        -193570837,
        -1617640095,
        -1359243304,
        -1973979577,
        -67333094,
        -2129721489,
        2110766901,
        -1121163101,
        866345822,
        -297497515,
        -736375570,
        30280569,
        -1199907813,
        1887084032,
        519330814,
        -2050877083,
        174127839,
        1712524135,
        -861378278,
        793184536,
        174500816,
        237039773,
        944491332
    );

    public enum ObservationType {
        Read(0),
        ReadSegment(1),
        EndOfReadSegment(2),
        EOM(3),
        Write(4),
        WriteSegment(5),
        EndOfWriteSegment(6),
        RequestDropped(7),
        Close(8);

        private final int intValue;

        private ObservationType(int intValue) {
            this.intValue = intValue;
        }

        public int getIntValue() {
            return intValue;
        }

        public static Stream<ObservationType> valueStream() {
            return Arrays.stream(ObservationType.values()).filter(ot -> ot != ObservationType.Close);
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
        return ((ot1.intValue) * CLASSIFY_COMPONENT_INT_SHIFT) + ot2.intValue;
    }

    public static String classificationToString(int c) {
        int left = c;
        int end = c % CLASSIFY_COMPONENT_INT_SHIFT;
        left = left / CLASSIFY_COMPONENT_INT_SHIFT;
        int start = left % CLASSIFY_COMPONENT_INT_SHIFT;
        left = left / CLASSIFY_COMPONENT_INT_SHIFT;
        int size = left % CLASSIFY_COMPONENT_INT_SHIFT;
        return start + "," + end + "," + size;
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
        } else if (trafficObservation.hasRequestDropped()) {
            return Optional.of(ObservationType.RequestDropped);
        } else {
            throw new IllegalStateException("unknown traffic observation: " + trafficObservation);
        }
    }

    private static ObservationType getTypeFromObservation(
        TrafficObservation trafficObservation,
        ObservationType lastObservationType
    ) {
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
                case RequestDropped:
                    return ObservationType.RequestDropped;
                default:
                    throw new IllegalStateException(
                        "previous observation type doesn't match expected possibilities: " + lastObservationType
                    );
            }
        });
    }

    public static int classifyTrafficStream(HashSet<Integer> possibilitiesLeftToTest, TrafficStream[] trafficStreams) {
        int counter = 0;
        ObservationType previousObservationType = ObservationType.Read;
        for (var ts : trafficStreams) {
            var ssl = ts.getSubStreamList();
            ObservationType outgoingLastType = previousObservationType;
            for (int i = ssl.size() - 1; i >= 0; --i) {
                var outgoingLastTypeOp = getTypeFromObservation(ssl.get(i));
                if (!outgoingLastTypeOp.isEmpty()) {
                    outgoingLastType = outgoingLastTypeOp.get();
                    break;
                }
            }
            var type = makeClassificationValue(
                getTypeFromObservation(ssl.get(0), previousObservationType),
                getTypeFromObservation(ssl.get(ssl.size() - 1), outgoingLastType),
                ssl.size()
            );
            log.atTrace().setMessage(() -> "classification=" + classificationToString(type)).log();
            previousObservationType = outgoingLastType;
            counter += possibilitiesLeftToTest.remove(type) ? 1 : 0;
        }
        return counter;
    }

    private static void addCommands(
        Random r,
        double flushLikelihood,
        int numPacketCommands,
        List<ObservationDirective> outgoingCommands,
        List<Integer> outgoingSizes,
        Supplier<ObservationDirective> directiveSupplier
    ) {
        int aggregateBufferSize = 0;
        for (var cmdCount = new AtomicInteger(numPacketCommands); cmdCount.get() > 0;) {
            var cmd = getFlushOrSupply(r, flushLikelihood, cmdCount, directiveSupplier);
            outgoingCommands.add(cmd);
            aggregateBufferSize += cmd.size;
        }
        outgoingSizes.add(aggregateBufferSize);
    }

    private static ObservationDirective getFlushOrSupply(
        Random r,
        double flushLikelihood,
        AtomicInteger i,
        Supplier<ObservationDirective> supplier
    ) {
        return supplyRandomly(r, flushLikelihood, () -> ObservationDirective.flush(), () -> {
            i.decrementAndGet();
            return supplier.get();
        });
    }

    private static <T> T supplyRandomly(Random r, double p1, Supplier<T> supplier1, Supplier<T> supplier2) {
        return (r.nextDouble() <= p1) ? supplier1.get() : supplier2.get();
    }

    private static void fillCommandsAndSizes(
        Random r,
        double cancelRequestLikelihood,
        double flushLikelihood,
        int bufferBound,
        List<ObservationDirective> commands,
        List<Integer> sizes
    ) {
        var numTransactions = r.nextInt(MAX_COMMANDS_IN_CONNECTION);
        for (int i = numTransactions; i > 0; --i) {
            addCommands(
                r,
                flushLikelihood,
                r.nextInt(MAX_READS_IN_REQUEST) + 1,
                commands,
                sizes,
                () -> ObservationDirective.read(r.nextInt(bufferBound))
            );
            if (r.nextDouble() <= cancelRequestLikelihood) {
                commands.add(ObservationDirective.cancelOffload());
                ++i; // compensate to get the right number of requests in the loop
                sizes.remove(sizes.size() - 1); // This won't show up as a request, so don't propagate it
                continue;
            }
            if (r.nextDouble() <= flushLikelihood) {
                commands.add(ObservationDirective.flush());
            }
            commands.add(ObservationDirective.eom());
            addCommands(
                r,
                flushLikelihood,
                r.nextInt(MAX_WRITES_IN_RESPONSE) + 1,
                commands,
                sizes,
                () -> ObservationDirective.write(r.nextInt(bufferBound))
            );
            if (r.nextDouble() <= flushLikelihood) {
                commands.add(ObservationDirective.flush());
            }
        }
    }

    @SneakyThrows
    private static TrafficStream[] fillCommandsAndSizesForSeed(
        TestContext rootContext,
        long rSeed,
        AtomicInteger uniqueIdCounter,
        ArrayList<ObservationDirective> commands,
        ArrayList<Integer> sizes
    ) {
        var r2 = new Random(rSeed);
        var bufferSize = r2.nextInt(MAX_BUFFER_SIZE - MIN_BUFFER_SIZE) + MIN_BUFFER_SIZE;
        final var bufferBound = (int) (Math.abs(r2.nextGaussian()) * ((MAX_BUFFER_SIZE_MULTIPLIER * bufferSize))) + 1;
        log.atTrace()
            .setMessage(
                () -> String.format(
                    "bufferSize=%d bufferBound=%d maxPossibleReads=%d maxPossibleWrites=%d",
                    bufferSize,
                    bufferBound,
                    MAX_READS_IN_REQUEST,
                    MAX_WRITES_IN_RESPONSE
                )
            )
            .log();
        var flushLikelihood = Math.pow(r2.nextDouble(), 2.0);
        fillCommandsAndSizes(r2, flushLikelihood / 4, flushLikelihood, bufferBound, commands, sizes);
        return TrafficStreamGenerator.makeTrafficStream(
            bufferSize,
            (int) rSeed,
            uniqueIdCounter,
            commands,
            rootContext
        );
    }

    /**
     * track all possibilities of start + end per a TrafficStream.
     * notice that one created sequence of streams may have a number of different classifications of streams
     */
    public static HashSet<Integer> getPossibleTests() {
        // these transitions are impossible to do within one TrafficStream since segmented reads are only required
        // when the span multiple records
        var impossibleTransitions = Map.of(
            ObservationType.Read,
            List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment),
            ObservationType.ReadSegment,
            List.of(ObservationType.EndOfWriteSegment),
            ObservationType.EndOfReadSegment,
            List.of(ObservationType.EndOfWriteSegment),
            ObservationType.EOM,
            List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment),
            ObservationType.Write,
            List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment),
            ObservationType.WriteSegment,
            List.of(ObservationType.EndOfReadSegment),
            ObservationType.EndOfWriteSegment,
            List.of(ObservationType.EndOfReadSegment),
            ObservationType.RequestDropped,
            List.of(ObservationType.EndOfReadSegment, ObservationType.EndOfWriteSegment)
        )
            .entrySet()
            .stream()
            .flatMap(kvp -> kvp.getValue().stream().map(v -> makeClassificationValue(kvp.getKey(), v, 0)))
            .collect(Collectors.toSet());
        var possibilities = new HashSet<Integer>();
        ObservationType.valueStream()
            .flatMap(ot1 -> ObservationType.valueStream().map(ot2 -> makeClassificationValue(ot1, ot2, 0)))
            .filter(i -> !impossibleTransitions.contains(i))
            .forEach(possibilities::add);
        return possibilities;
    }

    @AllArgsConstructor
    public static class RandomTrafficStreamAndTransactionSizes {
        public final int randomSeedUsed;
        public final TrafficStream[] trafficStreams;
        public final int[] requestByteSizes;
        public final int[] responseByteSizes;
    }

    @AllArgsConstructor
    public static class StreamAndExpectedSizes {
        public final Stream<TrafficStream> stream;
        public final int numHttpTransactions;
    }

    public static StreamAndExpectedSizes generateStreamAndSumOfItsTransactions(
        TestContext rootContext,
        int count,
        boolean randomize
    ) {
        var generatedCases = count > 0
            ? generateRandomTrafficStreamsAndSizes(rootContext, IntStream.range(0, count))
            : generateAllIndicativeRandomTrafficStreamsAndSizes(rootContext);
        var testCaseArr = generatedCases.toArray(RandomTrafficStreamAndTransactionSizes[]::new);
        log.atInfo()
            .setMessage(
                () -> "test case array = \n"
                    + Arrays.stream(testCaseArr)
                        .flatMap(tc -> Arrays.stream(tc.trafficStreams).map(TrafficStreamUtils::summarizeTrafficStream))
                        .collect(Collectors.joining("\n"))
            )
            .log();
        var aggregatedStreams = randomize
            ? StreamInterleaver.randomlyInterleaveStreams(
                new Random(count),
                Arrays.stream(testCaseArr).map(c -> Arrays.stream(c.trafficStreams))
            )
            : Arrays.stream(testCaseArr).flatMap(c -> Arrays.stream(c.trafficStreams));

        var numExpectedRequests = Arrays.stream(testCaseArr).mapToInt(c -> c.requestByteSizes.length).sum();
        return new StreamAndExpectedSizes(aggregatedStreams, numExpectedRequests);
    }

    @AllArgsConstructor
    public static class TransactionSizesPair {
        public final int[] requestSizes;
        public final int[] responseSizes;
    }

    public static TransactionSizesPair unzipRequestResponseSizes(List<Integer> collatedList) {
        return new TransactionSizesPair(
            IntStream.range(0, collatedList.size()).filter(i -> (i % 2) == 0).map(i -> collatedList.get(i)).toArray(),
            IntStream.range(0, collatedList.size()).filter(i -> (i % 2) == 1).map(i -> collatedList.get(i)).toArray()
        );
    }

    public static Stream<RandomTrafficStreamAndTransactionSizes> generateRandomTrafficStreamsAndSizes(
        TestContext rootContext,
        IntStream seedStream
    ) {
        var uniqueIdCounter = new AtomicInteger();
        return seedStream.mapToObj(rSeed -> {
            var commands = new ArrayList<ObservationDirective>();
            var sizes = new ArrayList<Integer>();
            var trafficStreams = fillCommandsAndSizesForSeed(rootContext, rSeed, uniqueIdCounter, commands, sizes);

            var splitSizes = unzipRequestResponseSizes(sizes);
            return new RandomTrafficStreamAndTransactionSizes(
                rSeed,
                trafficStreams,
                splitSizes.requestSizes,
                splitSizes.responseSizes
            );
        }).filter(o -> o != null);
    }

    public static Stream<RandomTrafficStreamAndTransactionSizes> generateAllIndicativeRandomTrafficStreamsAndSizes(
        TestContext rootContext
    ) {
        return generateRandomTrafficStreamsAndSizes(
            rootContext,
            RANDOM_GENERATOR_SEEDS_FOR_SUFFICIENT_TRAFFIC_VARIANCE.stream().mapToInt(i -> i)
        );
    }
}
