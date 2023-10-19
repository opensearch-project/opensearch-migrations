package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vavr.Tuple2;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.replay.datatypes.RawPackets;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Some things to consider - Reads, Writes, ReadSegments, WriteSegments, EndOfSegment, EndOfMessage
 * In a well-formed stream, these will always occur in the pattern
 *   `(Read | (ReadSegment* EndOfSegment))* EndOfMessage (Write | (WriteSegment* EndOfSegnemt))*`
 *
 * The pattern may be repeated any number of times and that pattern may be truncated at any point.
 * However, once a stream of observations is truncated, it is a permanent truncation.  No additional
 * observations will be present, making truncations but not excisions possible.
 * @return
 */
@Slf4j
public class CapturedTrafficToHttpTransactionAccumulatorTest {

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
        public static ObservationDirective flush(int i) {
            return new ObservationDirective(OffloaderCommandType.Flush, 0);
        }
    }

    public InMemoryConnectionCaptureFactory buildSerializerFactory(int bufferSize, Runnable onClosedCallback) {
        return new InMemoryConnectionCaptureFactory("TEST_NODE_ID", bufferSize, onClosedCallback);
    }

    ByteBuf makeSequentialByteBuf(int offset, int size) {
        var bb = Unpooled.buffer(size);
        for (int i=0; i<size; ++i) {
            //bb.writeByte((i+offset)%255);
            bb.writeByte('A'+offset);
        }
        return bb;
    }

    Stream<TrafficStream> makeTrafficStreams(int bufferSize, int interactionOffset,
                                             ObservationDirective[] directives)
            throws Exception {
        var connectionFactory = buildSerializerFactory(bufferSize, ()->{});
        var offloader = connectionFactory.createOffloader("TEST_CONNECTION_ID");
        for (var directive : directives) {
            offloadHttpTransaction(offloader, interactionOffset++, directive);
        }
        offloader.addCloseEvent(Instant.EPOCH);
        offloader.flushCommitAndResetStream(true).get();
        return connectionFactory.getRecordedTrafficStreamsStream();
    }

    private void offloadHttpTransaction(IChannelConnectionCaptureSerializer offloader, int offset,
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

    @ParameterizedTest(name="{0}")
    @MethodSource("loadCombinations")
    void test(String testName, int bufferSize, int expectedCount, int skipCount,
              ObservationDirective[] directives, int[] expectedSizes)
            throws Exception {

        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30), null,
                        (id,request) -> requestsReceived.incrementAndGet(),
                        fullPair -> reconstructedTransactions.add(fullPair),
                        (rk,ts) -> {}
                );
        var trafficStreams = makeTrafficStreams(bufferSize, 0, directives).skip(skipCount);
        var tsList = trafficStreams.collect(Collectors.toList());
        trafficStreams = tsList.stream();
        trafficStreams.forEach(ts->trafficAccumulator.accept(new TrafficStreamWithEmbeddedKey(ts)));
        log.error("reconstructedTransactions="+reconstructedTransactions);
        Assertions.assertEquals(expectedCount, reconstructedTransactions.size());
        for (int i=0; i<reconstructedTransactions.size(); ++i) {
            Assertions.assertEquals((long) expectedSizes[i*2],
                    aggregateSizeOfPacketBytes(reconstructedTransactions.get(i).requestData.packetBytes));
            Assertions.assertEquals((long) expectedSizes[i*2+1],
                    aggregateSizeOfPacketBytes(reconstructedTransactions.get(i).responseData.packetBytes));
        }
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
        trafficStreams.close();
        Assertions.assertEquals(expectedCount, reconstructedTransactions.size());
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
    }

    public static Arguments[] loadCombinations() {
        return new Arguments[] {
                Arguments.of("easyTransactionsAreHandledCorrectly",
                        1024*1024, 1, 0,
                        new ObservationDirective[]{
                                ObservationDirective.read(1024),
                                ObservationDirective.eom(),
                                ObservationDirective.write(1024)
                        },
                        new int[]{1024, 1024}),
                Arguments.of("transactionStartingWithSegmentsAreHandledCorrectly",
                        1024, 1, 0,
                        new ObservationDirective[]{
                                ObservationDirective.read(1024),
                                ObservationDirective.eom(),
                                ObservationDirective.write(1024)
                        },
                        new int[]{1024, 1024}),
                Arguments.of("skippedTrafficStreamWithNextStartingOnSegmentsAreHandledCorrectly",
                        512, 1, 1,
                        new ObservationDirective[]{
                                ObservationDirective.read(256),
                                ObservationDirective.eom(),
                                ObservationDirective.write(256),
                                ObservationDirective.read(256),
                                ObservationDirective.eom(),
                                ObservationDirective.write(256)
                        },
                        new int[]{256, 256, 256, 256})
        };
    }
}
