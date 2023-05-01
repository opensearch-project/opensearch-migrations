package org.opensearch.migrations.replay.datahandlers.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.stream.ChunkedStream;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.ReplayUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class HttpParser {
    private final EmbeddedChannel channel;

    public HttpParser() {
        channel = new EmbeddedChannel(
                new HttpRequestDecoder(),
                new HttpStreamHandler());
    }

    public void write(byte[] nextRequestPacket) {
        log.error("Writing into embedded channel: "+ new String(nextRequestPacket, StandardCharsets.UTF_8));
        channel.writeInbound(Unpooled.wrappedBuffer(nextRequestPacket));
    }

    public HttpMessageAsJson asJsonDocument() {
        var httpHandler = (HttpStreamHandler) channel.pipeline().last();
        var packagedRequest = new HttpMessageAsJson();
        packagedRequest.setUri(httpHandler.getRequest().uri().toString());
        packagedRequest.setMethod(httpHandler.getRequest().method().toString());
        // TODO - pull the exact HTTP version string from the packets.
        // This is an example of where netty moves too far away from the source for our needs
        log.info("TODO: pull the exact HTTP version string from the packets instead of hardcoding it.");
        packagedRequest.setProtocol("HTTP/1.1");
        log.info("TODO: Copying header NAMES over through a lowercase transformation that is currently NOT preserved " +
                "when sending the response");
        var headers = httpHandler.getRequest().headers().entries().stream()
                .collect(Collectors.groupingBy(kvp->kvp.getKey().toLowerCase(),
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        packagedRequest.setHeaders(headers);
        packagedRequest.setPayload(new LazyLoadingPayloadMap(headers, ()->httpHandler.getBodyStream()));
        return packagedRequest;
    }

    public class HttpStreamHandler extends ChannelInboundHandlerAdapter {
        private HttpRequest request;
        private ArrayList<ByteBuf> bodyChunks;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;

            } else if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                if (bodyChunks == null) {
                    bodyChunks = new ArrayList<>();
                }
                bodyChunks.add(content.content());
            }
        }

        public HttpRequest getRequest() {
            return request;
        }

        public InputStream getBodyStream() {
            return ReplayUtils.byteArraysToInputStream(bodyChunks.stream()
                    .map(bb->bb.duplicate())
                    .map(bb-> {
                        var bArr = new byte[bb.readableBytes()];
                        bb.readBytes(bArr);
                        return bArr;
                    }));
        }
    }
}