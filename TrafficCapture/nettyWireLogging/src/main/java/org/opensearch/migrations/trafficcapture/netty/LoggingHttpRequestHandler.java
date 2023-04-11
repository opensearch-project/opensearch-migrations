package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpVersion;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LoggingHttpRequestHandler extends ChannelInboundHandlerAdapter {
    public static class SimpleHttpRequestDecoder extends HttpRequestDecoder {
        /**
         * Override to broaden the visibility.
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
         * @param buffer            the {@link ByteBuf} from which to read data
         * @param out           the {@link List} to which decoded messages should be added
         * @throws Exception
         */
        @Override
        public void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
            super.decode(ctx, buffer, out);
        }

        @Override
        public void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            super.decodeLast(ctx, in, out);
        }

        @Override
        public HttpMessage createMessage(String[] initialLine) throws Exception {
             return new DefaultHttpRequest(HttpVersion.valueOf(initialLine[2]),
                    HttpMethod.valueOf(initialLine[0]), initialLine[1]
                    , new PassThruHttpHeaders()
            );
        }
    }

    private final IChannelConnectionCaptureSerializer trafficOffloader;

    private final SimpleHttpRequestDecoder decoder;
    public LoggingHttpRequestHandler(IChannelConnectionCaptureSerializer trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
        decoder = new SimpleHttpRequestDecoder();
    }

    protected void onHttpObjectsDecoded(List<Object> parsedMsgs) throws IOException {
        HttpCaptureSerializerUtil.addHttpMessageIndicatorEvents(trafficOffloader, parsedMsgs);
    }

    private void parseHttpMessageParts(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        var bb = msg;
        bb.retain();
        bb.markReaderIndex();
        // todo - move this into a pool
        var parsedMsgs = new ArrayList<>(4);
        decoder.decode(ctx, bb, parsedMsgs);
        bb.resetReaderIndex();
        onHttpObjectsDecoded(parsedMsgs);
    }


    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        trafficOffloader.flush(true);
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        trafficOffloader.addReadEvent(Instant.now(), (ByteBuf) msg);
        parseHttpMessageParts(ctx, (ByteBuf) msg);
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        super.channelReadComplete(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

}
