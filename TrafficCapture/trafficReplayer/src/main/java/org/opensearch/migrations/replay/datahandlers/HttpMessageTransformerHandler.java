package org.opensearch.migrations.replay.datahandlers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpVersion;
import org.opensearch.migrations.replay.datahandlers.PacketToTransformingHttpMessageHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class HttpMessageTransformerHandler extends PacketToTransformingHttpMessageHandler {
    public HttpMessageTransformerHandler(IPacketToHttpHandler httpHandler) {
        super(httpHandler, createHttpMessageTransformer());
    }

    private static ByteTransformer createHttpMessageTransformer() {
        return new ByteTransformer() {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            @Override
            public CompletableFuture<Void> addBytes(byte[] nextRequestPacket) {
                var parsedMsgs = new ArrayList<>(4);
                try {
                    byteArrayOutputStream.write(nextRequestPacket);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<SizeAndInputStream> getFullyTransformedBytes() {
                var buff = byteArrayOutputStream.toByteArray();
                return CompletableFuture.completedFuture(
                        new SizeAndInputStream(buff.length, new ByteArrayInputStream(buff)));
            }
        };
    }
}
