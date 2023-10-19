package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vavr.Tuple2;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class CapturedTrafficToHttpTransactionAccumulatorTest {

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

    Stream<TrafficStream> makeTrafficStreams(int bufferSize, List<Tuple2<Integer,Integer>> transactionSizeSplits)
            throws Exception {
        var connectionFactory = buildSerializerFactory(bufferSize, ()->{});
        var offloader = connectionFactory.createOffloader("TEST_CONNECTION_ID");
        var offset = 0;
        for (var rw : transactionSizeSplits) {
            offloadHttpTransaction(offloader, offset++, rw._1(), rw._2());
        }
        offloader.addCloseEvent(Instant.EPOCH);
        offloader.flushCommitAndResetStream(true).get();
        return connectionFactory.getRecordedTrafficStreamsStream();
    }

    private void offloadHttpTransaction(IChannelConnectionCaptureSerializer offloader, int offset,
                                        int readBufferSize, int writeBufferSize) throws IOException {
        offloader.addReadEvent(Instant.EPOCH, makeSequentialByteBuf(offset, readBufferSize));
        offloader.commitEndOfHttpMessageIndicator(Instant.EPOCH);
        offloader.addWriteEvent(Instant.EPOCH, makeSequentialByteBuf(offset+3, writeBufferSize));
    }

    long aggregateSizeOfPacketBytes(RawPackets packetBytes) {
        return packetBytes.stream().mapToInt(bArr->bArr.length).sum();
    }

    void test(int bufferSize, int expectedCount, int skipCount, List<Tuple2<Integer, Integer>> rwPairs) throws Exception {
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30), null,
                        (id,request) -> requestsReceived.incrementAndGet(),
                        fullPair -> reconstructedTransactions.add(fullPair),
                        (rk,ts) -> {}
                );
        Stream<TrafficStream> trafficStreams = makeTrafficStreams(bufferSize, rwPairs).skip(skipCount);
        var tsList = trafficStreams.collect(Collectors.toList());
        trafficStreams = tsList.stream();
        trafficStreams.forEach(ts->trafficAccumulator.accept(new TrafficStreamWithEmbeddedKey(ts)));
        log.error("reconstructedTransactions="+reconstructedTransactions);
        Assertions.assertEquals(expectedCount, reconstructedTransactions.size());
        for (int i=0; i<reconstructedTransactions.size(); ++i) {
            Assertions.assertEquals((long) rwPairs.get(i)._1(),
                    aggregateSizeOfPacketBytes(reconstructedTransactions.get(i).requestData.packetBytes));
            Assertions.assertEquals((long) rwPairs.get(i)._2(),
                    aggregateSizeOfPacketBytes(reconstructedTransactions.get(i).responseData.packetBytes));
        }
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
        trafficStreams.close();
        Assertions.assertEquals(expectedCount, reconstructedTransactions.size());
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
    }

    //@Test
    public void myTest() throws Exception {
        testThatSkippedTrafficStreamWithNextNextStartingOnSegmentsAreHandledCorrectly();
        //log.error("finished test and moving to the next one");
        testThatEasyTransactionsAreHandledCorrectly();

    }

    @Test
    public void testThatEasyTransactionsAreHandledCorrectly() throws Exception {
        test(1024*1024, 1, 0, List.of(new Tuple2(1024, 1024)));
    }

    @Test
    public void testThatTransactionStartingWithSegmentsAreHandledCorrectly() throws Exception {
        test(1024, 1, 0, List.of(new Tuple2(1024, 1024)));
    }

    @Test
    public void testThatSkippedTrafficStreamWithNextNextStartingOnSegmentsAreHandledCorrectly() throws Exception {
        test(512, 1, 1, List.of(
                new Tuple2(256, 256),
                new Tuple2(256, 256)));
    }

}
