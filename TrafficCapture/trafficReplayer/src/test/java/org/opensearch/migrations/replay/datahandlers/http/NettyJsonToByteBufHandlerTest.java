package org.opensearch.migrations.replay.datahandlers.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection
public class NettyJsonToByteBufHandlerTest {

    public static final int READ_CHUNK_SIZE_BOUND = 4;// 4096;
    public static final int ORIGINAL_NUMBER_OF_CHUNKS = 4;
    public static final int ADDITIONAL_CHUNKS = 2;

    @Test
    public void testThatHttpContentsAreRepackagedToChunkSizeSpec() {
        for (int i = 0; i < 10; ++i) {
            log.info("Testing w/ random seed=" + i);
            testWithSeed(new Random(i));
            System.gc();
            System.runFinalization();
        }
    }

    public void testWithSeed(Random random) {
        List<List<Integer>> sharedInProgressChunkSizes = new ArrayList<>(2);
        sharedInProgressChunkSizes.add(NettyJsonToByteBufHandler.ZERO_LIST);
        sharedInProgressChunkSizes.add(new ArrayList<>());
        var channel = new EmbeddedChannel(new NettyJsonToByteBufHandler(sharedInProgressChunkSizes));
        Supplier<Integer> genRandom = () -> random.nextInt(READ_CHUNK_SIZE_BOUND - 1) + 1;
        var asCapturedSizes = sharedInProgressChunkSizes.get(1);
        IntStream.range(0, ORIGINAL_NUMBER_OF_CHUNKS) // fill the incoming chunks
            .forEach(i -> asCapturedSizes.add(genRandom.get()));
        var preSizesFromUpstreamHandler = new ArrayList<Integer>();
        var totalBytesWritten = IntStream.range(0, ORIGINAL_NUMBER_OF_CHUNKS + ADDITIONAL_CHUNKS) // simulate data going
                                                                                                  // through the
                                                                                                  // pipeline and being
                                                                                                  // changed and now
                                                                                                  // landing
            .map(i -> writeAndCheck(channel, preSizesFromUpstreamHandler, genRandom.get()))
            .sum();
        channel.close();

        var chunkSizesReceivedList = getByteBufSizesFromChannel(channel);
        Assertions.assertEquals(totalBytesWritten, chunkSizesReceivedList.stream().mapToInt(x -> x).sum());
        var commonLength = Math.min(asCapturedSizes.size(), chunkSizesReceivedList.size()) - 1;
        Assertions.assertArrayEquals(
            asCapturedSizes.subList(0, commonLength).toArray(),
            chunkSizesReceivedList.subList(0, commonLength).toArray()
        );
        var postSizesPastSourceConstrained = commonLength + 1 < chunkSizesReceivedList.size()
            ? chunkSizesReceivedList.subList(commonLength + 2, chunkSizesReceivedList.size())
            : List.of();
        Assertions.assertArrayEquals(
            preSizesFromUpstreamHandler.subList(
                preSizesFromUpstreamHandler.size() - postSizesPastSourceConstrained.size(),
                preSizesFromUpstreamHandler.size()
            ).toArray(),
            postSizesPastSourceConstrained.toArray()
        );

        Assertions.assertTrue(chunkSizesReceivedList.size() > sharedInProgressChunkSizes.size() + 1);
    }

    private static List<Integer> getByteBufSizesFromChannel(EmbeddedChannel channel) {
        return channel.inboundMessages().stream().map(x -> {
            var bb = ((ByteBuf) x);
            try {
                return bb.readableBytes();
            } finally {
                bb.release();
            }
        }).collect(Collectors.toList());
    }

    static byte nonce = 0;

    private int writeAndCheck(EmbeddedChannel channel, ArrayList<Integer> sizesWrittenList, int len) {
        var bytes = new byte[len];
        log.debug("Writing " + len);
        sizesWrittenList.add(len);
        Arrays.fill(bytes, nonce++);
        var httpContent = new DefaultHttpContent(Unpooled.wrappedBuffer(bytes));
        channel.writeInbound(httpContent);
        return len;
    }
}
