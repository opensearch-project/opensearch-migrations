package org.opensearch.migrations.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExhaustiveCapturedTrafficToHttpTransactionAccumulatorTest extends InstrumentationTest {

    public static Arguments[] generateAllTestsAndConfirmComplete(IntStream seedStream) {
        var rootContext = TestContext.noOtelTracking();
        var possibilitiesLeftToTest = ExhaustiveTrafficStreamGenerator.getPossibleTests();
        var numTries = new AtomicInteger();
        StringJoiner seedsThatOfferUniqueTestCases = new StringJoiner(",");
        var argsArray = ExhaustiveTrafficStreamGenerator.generateRandomTrafficStreamsAndSizes(rootContext, seedStream)
            .takeWhile(c -> !possibilitiesLeftToTest.isEmpty())
            .filter(
                c -> ExhaustiveTrafficStreamGenerator.classifyTrafficStream(
                    possibilitiesLeftToTest,
                    c.trafficStreams
                ) > 0
            )
            .flatMap(c -> {
                seedsThatOfferUniqueTestCases.add(c.randomSeedUsed + "");
                var n = numTries.getAndIncrement();
                log.info(
                    "Found new cases to test with seed="
                        + c.randomSeedUsed
                        + " tries="
                        + n
                        + " left="
                        + possibilitiesLeftToTest.size()
                );

                return IntStream.range(0, c.trafficStreams.length)
                    .mapToObj(
                        i -> Arguments.of(
                            "seed=" + c.randomSeedUsed,
                            i,
                            c.trafficStreams,
                            c.requestByteSizes,
                            c.responseByteSizes
                        )
                    );
            })
            .toArray(Arguments[]::new);

        // Assertions.assertTrue(possibilitiesLeftToTest.isEmpty());
        log.atInfo()
            .setMessage(() -> "Sufficient random seeds to generate a full cover of tests:{}")
            .addArgument(seedsThatOfferUniqueTestCases.toString())
            .log();
        return argsArray;
    }

    static Arguments[] generateTestCombinations() {
        var rand = new Random(1);
        return generateAllTestsAndConfirmComplete(
            // IntStream.generate(()->rand.nextInt())
            ExhaustiveTrafficStreamGenerator.RANDOM_GENERATOR_SEEDS_FOR_SUFFICIENT_TRAFFIC_VARIANCE
                // List.of(2110766901)
                .stream()
                .mapToInt(i -> i)
        );
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("generateTestCombinations")
    public void testAccumulatedSplit(
        String testName,
        int cutPoint,
        TrafficStream[] trafficStreams,
        int[] expectedRequestSizes,
        int[] expectedResponseSizes
    ) {
        accumulateWithAccumulatorPairAtPoint(trafficStreams, cutPoint, expectedRequestSizes, expectedResponseSizes);
    }

    void accumulateWithAccumulatorPairAtPoint(
        TrafficStream[] trafficStreams,
        int cutPoint,
        int[] expectedRequestSizes,
        int[] expectedResponseSizes
    ) {
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        // some of the messages up to the cutPoint may not have been able to be fully committed (when the
        // messages rolled over past the initial cutPoint. In those cases, we have to rewind further back
        // for the second run to make sure that we pick up those partial messages that were not received
        // in the first pass.
        //
        // Notice that this may cause duplicates. That's by design. The system has an at-least-once guarantee.
        var indicesProcessedPass1 = SimpleCapturedTrafficToHttpTransactionAccumulatorTest
            .accumulateTrafficStreamsWithNewAccumulator(
                rootContext,
                Arrays.stream(trafficStreams).limit(cutPoint),
                reconstructedTransactions,
                requestsReceived
            );
        cutPoint = indicesProcessedPass1.isEmpty() ? 0 : indicesProcessedPass1.last();
        var indicesProcessedPass2 = SimpleCapturedTrafficToHttpTransactionAccumulatorTest
            .accumulateTrafficStreamsWithNewAccumulator(
                rootContext,
                Arrays.stream(trafficStreams).skip(cutPoint),
                reconstructedTransactions,
                requestsReceived
            );

        // three checks to do w/ the indicesProcessed sets.
        // Count their sum, confirm that there were not duplicates, confirm all match the input indices
        Assertions.assertEquals(trafficStreams.length, indicesProcessedPass1.size() + indicesProcessedPass2.size());
        var unionSet = new TreeSet(indicesProcessedPass1);
        unionSet.addAll(indicesProcessedPass2);
        Assertions.assertEquals(trafficStreams.length, unionSet.size());
        Assertions.assertEquals(1, unionSet.first());
        Assertions.assertEquals(
            TrafficStreamUtils.getTrafficStreamIndex(trafficStreams[trafficStreams.length - 1]),
            unionSet.last()
        );

        SimpleCapturedTrafficToHttpTransactionAccumulatorTest.assertReconstructedTransactionsMatchExpectations(
            reconstructedTransactions,
            expectedRequestSizes,
            expectedResponseSizes
        );
    }
}
