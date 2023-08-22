package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.IntStream;

@Slf4j
class NettyJsonToByteBufHandlerTest {

    public static final int BUFFERED_THROUGHPUT_BOUND = 64;//16*1024;
    public static final int READ_CHUNK_SIZE_BOUND = 4;//4096;
    public static final int ORIGINAL_NUMBER_OF_CHUNKS = 4;
    public static final int ADDITIONAL_CHUNKS = 2;

    static class ValidationHandler extends ChannelInboundHandlerAdapter {
        final List<Integer> chunkSizesReceivedList;
        int totalBytesRead;

        public ValidationHandler() {
            chunkSizesReceivedList = new ArrayList();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                var incomingBytes = ((ByteBuf) msg).readableBytes();
                log.atWarn().setMessage(()->"validation handler got "+incomingBytes+" bytes").log();
                chunkSizesReceivedList.add(incomingBytes);
                totalBytesRead += incomingBytes;
            } else {
                throw new RuntimeException("Unknown incoming object "+msg);
            }
            super.channelRead(ctx, msg);
        }
    }

    @Test
    public void testThatHttpContentsAreRepackagedToChunkSizeSpec() {
        for (int i=0; i<10; ++i) {
            log.atError().log("Testing w/ random seed=", i);
            testWithSeed(new Random(i));
        }
    }

    public void testWithSeed(Random random) {
        List<List<Integer>> sharedInProgressChunkSizes = new ArrayList<>(2);
        sharedInProgressChunkSizes.add(NettyJsonToByteBufHandler.ZERO_LIST);
        sharedInProgressChunkSizes.add(new ArrayList<>());
        var validationHandler = new ValidationHandler();
        var channel = new EmbeddedChannel(
                new NettyJsonToByteBufHandler(sharedInProgressChunkSizes),
                validationHandler
        );
        Supplier<Integer> genRandom = () -> random.nextInt(READ_CHUNK_SIZE_BOUND-1)+1;
        var asCapturedSizes = sharedInProgressChunkSizes.get(1);
        IntStream.range(0, ORIGINAL_NUMBER_OF_CHUNKS) // fill the incoming chunks
                .forEach(i->asCapturedSizes.add(genRandom.get()));
        var preSizesFromUpstreamHandler = new ArrayList<Integer>();
        var totalBytesWritten = IntStream.range(0, ORIGINAL_NUMBER_OF_CHUNKS+ADDITIONAL_CHUNKS) // simulate data going through the pipeline and being changed and now landing
                .map(i->writeAndCheck(channel, preSizesFromUpstreamHandler, genRandom.get()))
                .sum();
        channel.close();
        Assertions.assertEquals(totalBytesWritten, validationHandler.totalBytesRead);
        var postSizes = validationHandler.chunkSizesReceivedList;
        var commonLength = Math.min(asCapturedSizes.size(), postSizes.size()) - 1;
        Assertions.assertArrayEquals(
                asCapturedSizes.subList(0, commonLength).toArray(),
                postSizes.subList(0, commonLength).toArray());
        var postSizesPastSourceConstrained =
                commonLength+1 < postSizes.size() ? postSizes.subList(commonLength+2, postSizes.size()) : List.of();
        Assertions.assertArrayEquals(preSizesFromUpstreamHandler.subList(preSizesFromUpstreamHandler.size()-postSizesPastSourceConstrained.size(),
                        preSizesFromUpstreamHandler.size()).toArray(),
                postSizesPastSourceConstrained.toArray());

        Assertions.assertTrue(validationHandler.chunkSizesReceivedList.size() >
                sharedInProgressChunkSizes.size()+1);
    }
    static byte nonce = 0;
    private int writeAndCheck(EmbeddedChannel channel, ArrayList<Integer> sizesWrittenList, int len) {
        var bytes = new byte[len];
        log.atWarn().setMessage(()->"Writing "+len).log();
        sizesWrittenList.add(len);
        Arrays.fill(bytes, nonce++);
        var httpContent = new DefaultHttpContent(Unpooled.wrappedBuffer(bytes));
        channel.writeInbound(httpContent);
        return len;
    }
}