//package org.opensearch.migrations.replay.datahandlers;
//
//import io.netty.buffer.ByteBuf;
//import io.netty.buffer.PooledByteBufAllocator;
//import io.netty.buffer.Unpooled;
//import io.netty.buffer.UnpooledHeapByteBuf;
//import lombok.extern.slf4j.Slf4j;
//import org.opensearch.migrations.replay.AggregatedRawResponse;
//import org.opensearch.migrations.replay.datahandlers.http.HttpMessageTransformer;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.util.concurrent.CompletableFuture;
//import java.util.function.Consumer;
//import java.util.function.Supplier;
//import java.util.stream.Stream;
//
//@Slf4j
//public abstract class PacketToTransformingHttpMessageHandler implements IPacketToHttpHandler {
//    private final IPacketToHttpHandler httpHandler;
//    private final HttpMessageTransformer transformer;
//    private int packetCount;
//
//    public PacketToTransformingHttpMessageHandler(IPacketToHttpHandler httpHandler, HttpMessageTransformer transformer) {
//        this.httpHandler = httpHandler;
//        this.transformer = transformer;
//    }
//
//    @Override
//    public CompletableFuture<Void> consumeBytes(byte[] nextRequestPacket) {
//        packetCount++;
//        return transformer.addBytes(nextRequestPacket);
//    }
//
//    @Override
//    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
//        CompletableFuture<Consumer<ByteBuf>> bbConsumerFuture = transformer.transformeFinalizedBytes();
//        int chunkSize = Math.max(1, sizeAndOutputStream.totalByteSize / packetCount);
//        var chunkConsumerFuture = getAndConsumeNextChunk(httpHandler,
//                Unpooled.EMPTY_BUFFER, sizeAndOutputStream.byteBufStream,
//                chunkSize, sizeAndOutputStream.totalByteSize, packetCount);
//        return chunkConsumerFuture.thenCompose(v -> httpHandler.finalizeRequest());
//
//        bbConsumerFuture.thenCompose(bbConsumer -> {
//            bbConsumer.accept();
//        });
//
//
//
//        return transformer.getFullyTransformedBytes().thenCompose(sizeAndOutputStream -> {
//            try {
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//    }
//
//    private static CompletableFuture<Void>
//    getAndConsumeNextChunk(IPacketToHttpHandler httpHandler,
//                           ByteBuf currentBuffer, Stream<ByteBuf> dataStream,
//                           int chunkSize, int bytesLeft, int packetsLeft) throws IOException {
//        if (bytesLeft <= 0) {
//            return CompletableFuture.completedFuture(null);
//        }
//        var nextBuffer = getNextBytes(currentBuffer, streamSupplier, chunkSize, bytesLeft, packetsLeft);
//        final int bufferLength = nextBuffer.length;
//        log.info("consuming "+nextBuffer.length+" bytes");
//        return httpHandler.consumeBytes(nextBuffer)
//                .thenCompose(v -> {
//                    try {
//                        log.info("getAndConsumeNextChunk has completed... recursing");
//                        return getAndConsumeNextChunk(httpHandler, dataStream, chunkSize,
//                                bytesLeft - bufferLength, packetsLeft - 1);
//                    } catch (IOException e) {
//                        throw new RuntimeException(e);
//                    }
//                });
//    }
//
//    final static byte[] EMPTY_ARRAY = new byte[0];
//    private static byte[] getNextBytes(ByteBuf currentBuffer, Stream<ByteBuf> dataStream, int chunkSize,
//                                int bytesLeft, int packetsLeft) throws IOException {
//        if (bytesLeft <= 0) {
//            return EMPTY_ARRAY;
//        } else if (packetsLeft <= 1) {
//            return inputStream.readAllBytes();
//        } else {
//            return inputStream.readNBytes(chunkSize);
//        }
//    }
//}
