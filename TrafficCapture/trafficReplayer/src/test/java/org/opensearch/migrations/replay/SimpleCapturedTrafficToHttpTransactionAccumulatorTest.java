package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.RawPackets;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.generator.ObservationDirective;
import org.opensearch.migrations.replay.traffic.generator.TrafficStreamGenerator;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
public class SimpleCapturedTrafficToHttpTransactionAccumulatorTest extends InstrumentationTest {
    public static final int MAX_COMMANDS_IN_CONNECTION = 256;

    static long calculateAggregateSizeOfPacketBytes(RawPackets packetBytes) {
        return packetBytes.stream().mapToInt(bArr -> bArr.length).sum();
    }

    public static Arguments[] loadSimpleCombinations() {
        return new Arguments[] {
            Arguments.of(
                "easyTransactionsAreHandledCorrectly",
                1024 * 1024,
                0,
                List.of(ObservationDirective.read(1024), ObservationDirective.eom(), ObservationDirective.write(1024)),
                List.of(1024, 1024)
            ),
            Arguments.of(
                "transactionStartingWithSegmentsAreHandledCorrectly",
                1024,
                0,
                List.of(ObservationDirective.read(1024), ObservationDirective.eom(), ObservationDirective.write(1024)),
                List.of(1024, 1024)
            ),
            Arguments.of(
                "skippedTrafficStreamWithNextStartingOnSegmentsAreHandledCorrectly",
                512,
                1,
                List.of(
                    ObservationDirective.read(MAX_COMMANDS_IN_CONNECTION),
                    ObservationDirective.eom(),
                    ObservationDirective.write(MAX_COMMANDS_IN_CONNECTION),
                    ObservationDirective.read(MAX_COMMANDS_IN_CONNECTION),
                    ObservationDirective.eom(),
                    ObservationDirective.write(MAX_COMMANDS_IN_CONNECTION)
                ),
                List.of(MAX_COMMANDS_IN_CONNECTION, MAX_COMMANDS_IN_CONNECTION)
            ) };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loadSimpleCombinations")
    void generateAndTest(
        String testName,
        int bufferSize,
        int skipCount,
        List<ObservationDirective> directives,
        List<Integer> expectedSizes
    ) throws Exception {
        final var trafficStreamsArray = TrafficStreamGenerator.makeTrafficStream(
            bufferSize,
            0,
            new AtomicInteger(),
            directives,
            rootContext
        );
        var trafficStreams = Arrays.stream(trafficStreamsArray).skip(skipCount);
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        accumulateTrafficStreamsWithNewAccumulator(
            rootContext,
            trafficStreams,
            reconstructedTransactions,
            requestsReceived
        );
        var splitSizes = ExhaustiveTrafficStreamGenerator.unzipRequestResponseSizes(expectedSizes);
        assertReconstructedTransactionsMatchExpectations(
            reconstructedTransactions,
            splitSizes.requestSizes,
            splitSizes.responseSizes
        );
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
    }

    /**
     * Returns the traffic stream indices whose contents have been fully received.
     * @param trafficStreams
     * @param aggregations
     * @param requestsReceived
     * @return
     */
    static SortedSet<Integer> accumulateTrafficStreamsWithNewAccumulator(
        TestContext context,
        Stream<TrafficStream> trafficStreams,
        List<RequestResponsePacketPair> aggregations,
        AtomicInteger requestsReceived
    ) {
        var tsIndicesReceived = new TreeSet<Integer>();
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
            new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    requestsReceived.incrementAndGet();
                    return fullPair -> {
                        var sourceIdx = ctx.getReplayerRequestKey().getSourceRequestIndex();
                        if (fullPair.completionStatus == RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY) {
                            return;
                        }
                        fullPair.getTrafficStreamsHeld()
                            .stream()
                            .forEach(tsk -> tsIndicesReceived.add(tsk.getTrafficStreamIndex()));
                        if (aggregations.size() > sourceIdx) {
                            var oldVal = aggregations.set(sourceIdx, fullPair);
                            if (oldVal != null) {
                                Assertions.assertEquals(oldVal, fullPair);
                            }
                        } else {
                            aggregations.add(fullPair);
                        }
                    };
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onConnectionClose(
                    int channelInteractionNumber,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int channelSessionNumber,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
                    tsIndicesReceived.add(ctx.getTrafficStreamKey().getTrafficStreamIndex());
                }
            });
        var tsList = trafficStreams.collect(Collectors.toList());
        trafficStreams = tsList.stream();

        trafficStreams.forEach(
            ts -> trafficAccumulator.accept(
                new PojoTrafficStreamAndKey(
                    ts,
                    PojoTrafficStreamKeyAndContext.build(ts, context::createTrafficStreamContextForTest)
                )
            )
        );
        trafficAccumulator.close();
        return tsIndicesReceived;
    }

    static void assertReconstructedTransactionsMatchExpectations(
        List<RequestResponsePacketPair> reconstructedTransactions,
        int[] expectedRequestSizes,
        int[] expectedResponseSizes
    ) {
        log.debug("reconstructedTransactions=" + reconstructedTransactions);
        Assertions.assertEquals(expectedRequestSizes.length, reconstructedTransactions.size());
        for (int i = 0; i < reconstructedTransactions.size(); ++i) {
            Assertions.assertEquals(
                (long) expectedRequestSizes[i],
                calculateAggregateSizeOfPacketBytes(reconstructedTransactions.get(i).requestData.packetBytes)
            );
            Assertions.assertEquals(
                (long) expectedResponseSizes[i],
                calculateAggregateSizeOfPacketBytes(reconstructedTransactions.get(i).responseData.packetBytes)
            );
        }
    }
}
